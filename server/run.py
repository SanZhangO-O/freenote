"""启动 FreeNote 同步服务端。

用法：
    python run.py                 # 监听 0.0.0.0:8000（HTTP）
    python run.py --port 9000     # 自定义端口
    python run.py --reload        # 开发热重载
    python run.py --ssl-certfile cert.pem --ssl-keyfile key.pem   # 启用 HTTPS

启用 HTTPS 后客户端应使用 https:// 地址；自签名证书需在客户端
设置中允许（Windows：勾选「跳过 HTTPS 证书校验」）。
"""

from __future__ import annotations

import argparse

import uvicorn


def main() -> None:
    parser = argparse.ArgumentParser(description="FreeNote sync server")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--reload", action="store_true", help="开发模式热重载")
    parser.add_argument("--ssl-certfile", default=None, help="TLS 证书（PEM），提供后启用 HTTPS")
    parser.add_argument("--ssl-keyfile", default=None, help="TLS 私钥（PEM），与证书配套使用")
    args = parser.parse_args()
    if bool(args.ssl_certfile) != bool(args.ssl_keyfile):
        parser.error("--ssl-certfile 与 --ssl-keyfile 必须同时提供")
    uvicorn.run(
        "freenote_server.app:app",
        host=args.host,
        port=args.port,
        reload=args.reload,
        ssl_certfile=args.ssl_certfile,
        ssl_keyfile=args.ssl_keyfile,
    )


if __name__ == "__main__":
    main()
