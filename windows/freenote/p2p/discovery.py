"""局域网设备发现：UDP 组播广播 + 单播应答。

- 每台设备周期性向组播组发送公告（设备 id/名称/同步端口/摘要）；
- 公告带「账户 + 口令」派生的 tag，不同账户或口令的设备互相不可见；
- 收到陌生设备的公告后，单播回一个公告，让双方尽快互相发现；
- 组播被防火墙阻止时，可在设置中手动添加 地址:端口。
"""

from __future__ import annotations

import json
import select
import socket
import struct
import threading
import time
from dataclasses import dataclass
from typing import Callable

from .. import config

_ANNOUNCE_MAGIC = "freenote-announce"

@dataclass
class Announcement:
    device_id: str
    name: str
    port: int
    digest: str = ""

    def to_wire(self, tag: str) -> bytes:
        return json.dumps(
            {
                "magic": _ANNOUNCE_MAGIC,
                "v": config.PROTOCOL_VERSION,
                "tag": tag,
                "id": self.device_id,
                "name": self.name,
                "port": self.port,
                "digest": self.digest,
            },
            ensure_ascii=False,
        ).encode("utf-8")

    @staticmethod
    def from_wire(data: bytes) -> "Announcement | None":
        try:
            payload = json.loads(data.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return None
        if not isinstance(payload, dict) or payload.get("magic") != _ANNOUNCE_MAGIC:
            return None
        device_id = str(payload.get("id") or "")
        if not device_id:
            return None
        try:
            port = int(payload.get("port") or 0)
        except (TypeError, ValueError):
            return None
        return Announcement(
            device_id=device_id,
            name=str(payload.get("name") or ""),
            port=port,
            digest=str(payload.get("digest") or ""),
        )

    @staticmethod
    def tag_of(data: bytes) -> str:
        try:
            payload = json.loads(data.decode("utf-8"))
            return str(payload.get("tag") or "")
        except (ValueError, UnicodeDecodeError):
            return ""

    @staticmethod
    def version_of(data: bytes) -> int:
        try:
            payload = json.loads(data.decode("utf-8"))
            return int(payload.get("v") or 0)
        except (ValueError, TypeError):
            return 0


class DiscoveryService:
    """UDP 组播发现。回调 on_peer(announcement, address, source_port)。"""

    def __init__(
        self,
        announce_provider: Callable[[], Announcement | None],
        tag_provider: Callable[[], str],
        on_peer: Callable[[Announcement, str, int], None],
        port: int = config.DISCOVERY_PORT,
        interval_sec: float = config.DISCOVERY_INTERVAL_SEC,
    ):
        self._announce_provider = announce_provider
        self._tag_provider = tag_provider
        self._on_peer = on_peer
        self._port = int(port)
        self._interval = max(1.0, float(interval_sec))
        self._sock: socket.socket | None = None
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._announce_requested = threading.Event()
        self._last_announce = 0.0
        self._last_reply = 0.0
        self._startup_until = 0.0
        self._send_lock = threading.Lock()
        self._self_id = ""

    # ------------------------------------------------------------------ 生命周期

    def start(self) -> bool:
        if self._thread is not None and self._thread.is_alive():
            return True
        sock: socket.socket | None = None
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
            except (AttributeError, OSError):
                pass
            sock.bind(("", self._port))
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 1)
            try:
                sock.setsockopt(
                    socket.IPPROTO_IP,
                    socket.IP_ADD_MEMBERSHIP,
                    struct.pack("4sl", socket.inet_aton(config.MULTICAST_GROUP), socket.INADDR_ANY),
                )
            except OSError:
                pass  # 组播不可用时仍可通过手动添加设备同步
            sock.setblocking(False)
        except OSError:
            if sock is not None:
                try:
                    sock.close()
                except OSError:
                    pass
            return False

        self._sock = sock
        self._stop.clear()
        # 启动后短时间内加快公告节奏，避免两台设备同时启动时互相错过首包、
        # 要等整个周期（8 秒）才重新公告。
        self._startup_until = time.monotonic() + 5.0
        self._thread = threading.Thread(target=self._loop, name="freenote-discovery", daemon=True)
        self._thread.start()
        return True

    def stop(self) -> None:
        self._stop.set()
        thread = self._thread
        if thread is not None and thread.is_alive():
            thread.join(timeout=3)
        self._thread = None
        sock, self._sock = self._sock, None
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass

    @property
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    # ------------------------------------------------------------------ 收发

    def set_self_id(self, device_id: str) -> None:
        self._self_id = device_id

    def announce_now(self) -> None:
        self._send_announcement()

    def request_announce(self) -> None:
        """请求尽快公告一次，实际发送交给发现线程。

        公告需要计算全库状态摘要（可能耗时），不能在 UI 线程同步执行；
        延迟到下一轮循环最多 0.5 秒，对同步时延没有影响。
        """
        self._announce_requested.set()

    def send_unicast(self, address: str, port: int) -> None:
        sock = self._sock
        if sock is None:
            return
        announcement = self._announce_provider()
        tag = self._tag_provider()
        if announcement is None or not tag:
            return
        self._send_to(sock, announcement.to_wire(tag), (address, int(port)))

    def _send_announcement(self) -> None:
        sock = self._sock
        if sock is None:
            return
        self._last_announce = time.monotonic()  # 未设置口令时也要限流，避免每次循环都计算摘要
        announcement = self._announce_provider()
        tag = self._tag_provider()
        if announcement is None or not tag:
            return
        data = announcement.to_wire(tag)
        self._send_to(sock, data, (config.MULTICAST_GROUP, self._port))
        try:
            self._send_to(sock, data, ("255.255.255.255", self._port))
        except OSError:
            pass

    def _send_to(self, sock: socket.socket, data: bytes, target: tuple[str, int]) -> None:
        with self._send_lock:
            try:
                sock.sendto(data, target)
            except OSError:
                pass

    def _loop(self) -> None:
        sock = self._sock
        if sock is None:
            return
        while not self._stop.is_set():
            now = time.monotonic()
            interval = 1.5 if now < self._startup_until else self._interval
            if self._announce_requested.is_set() or now - self._last_announce >= interval:
                self._announce_requested.clear()
                self._send_announcement()
            try:
                readable, _, _ = select.select([sock], [], [], 0.5)
            except (OSError, ValueError):
                break
            if not readable:
                continue
            try:
                data, sender = sock.recvfrom(4096)
            except OSError:
                continue
            self._handle_packet(data, sender)

    def _handle_packet(self, data: bytes, sender: tuple[str, int]) -> None:
        expected = self._tag_provider()
        if not expected or Announcement.tag_of(data) != expected:
            return
        if Announcement.version_of(data) != config.PROTOCOL_VERSION:
            return
        announcement = Announcement.from_wire(data)
        if announcement is None or announcement.device_id == self._self_id:
            return
        address = sender[0]
        # 尽快回敬一个单播公告，加速相互发现（组播延迟较高）；
        # 用独立的时间戳限流，避免两台设备互回公告造成乒乓。
        now = time.monotonic()
        if now - self._last_reply > 2.0:
            self._last_reply = now
            self.send_unicast(address, sender[1])
        try:
            self._on_peer(announcement, address, sender[1])
        except Exception:  # noqa: BLE001 - 回调异常不影响发现循环
            pass
