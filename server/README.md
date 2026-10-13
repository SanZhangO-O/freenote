# FreeNote 同步服务端

FreeNote 多设备同步服务：FastAPI + SQLite 单文件实现，无额外依赖，随起随用。
服务端只保存便签原文（锁定便签为密文），不解析、不索引内容。

## 运行

```bash
cd server
python -m pip install -r requirements.txt
python run.py                 # 监听 0.0.0.0:8000
python run.py --port 9000     # 自定义端口
```

环境要求：Python ≥ 3.10（使用了 `int | None` 类型注解）。

环境变量 `FREENOTE_SERVER_DB` 可指定数据库路径，默认 `server/data/freenote.db`。

启动后可访问 `http://<地址>:8000/docs` 查看交互式 API 文档。

## API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 健康检查（返回 `status` 与 `version`） |
| POST | `/api/register` | 注册 `{username, password}` → `{token, username}` |
| POST | `/api/login` | 登录，同上返回（令牌有效期 90 天） |
| POST | `/api/logout` | 注销当前令牌（需 Bearer Token） |
| POST | `/api/change_password` | 修改密码 `{oldPassword, newPassword}`；成功后撤销其他设备令牌 |
| POST | `/api/delete_account` | 注销账号 `{password}`：删除该用户在服务端的全部便签、图片与令牌 |
| POST | `/api/sync` | 推送本地变更并拉取增量（核心接口，含附件上传/下载） |
| GET | `/admin` | 只读管理面板（需设置环境变量 `FREENOTE_ADMIN_TOKEN` 后用 Bearer 头访问） |

密码相关的接口有滑动窗口限流（每 IP+用户名 5 分钟最多 10 次尝试，超出返回 429）。
限流键默认使用 TCP 对端地址；部署在反向代理后面时，设置环境变量
`FREENOTE_TRUSTED_PROXIES`（逗号分隔的可信代理 IP/CIDR，如 `127.0.0.1,::1`）
才会采信 `X-Forwarded-For`（取最右侧值）/`X-Real-IP`，未设置时一律忽略这些头，
防止客户端伪造 IP 绕过限流。

`POST /api/sync` 请求体：

```json
{
  "sinceSeq": 0,
  "notes": [ { "id": "...", "title": "...", "updatedAt": 1757500000000, "isDeleted": false, "recurrence": "", "...": "..." } ],
  "tombstones": [ { "id": "...", "deletedAt": 1757500000000 } ],
  "attachments": [ { "name": "a1b2c3d4e5f6a7b8.png", "data": "<base64，≤8MB>" } ],
  "wantAttachments": [ "0123456789abcdef.jpg" ]
}
```

响应：`{ "seq": N, "applied": n, "conflicts": n, "notes": [自 sinceSeq 之后的全部变更],
"attachments": [{name,data}], "attachmentsStored": [...], "capabilities": ["attachments"] }`。

- `capabilities` 用于客户端探测附件支持：旧版服务端没有该字段，客户端会自动降级为仅同步便签文本；
- 附件为内容寻址存储（文件名 = sha256 前 16 位十六进制 + 扩展名），服务端收到时校验哈希前缀；
  单文件上限 8 MB、单用户配额 512 MB（超出时该附件进入 `attachmentsRejected`）；
- 单次请求/响应携带附件 ≤8 个且 ≤8 MB 原始字节，超出部分由客户端下一轮继续；
- 请求体上限 32 MB（含 base64 膨胀后的附件）。

冲突策略：同一便签按 `updatedAt` 新者胜，更旧的写入被计入 `conflicts` 并保留服务端版本；同毫秒冲突时删除标记优先；删除标记也相同时内容哈希小者胜（确定性仲裁），哈希相同视为幂等。

## 管理面板

设置环境变量并重启后，用 `Authorization: Bearer <管理令牌>` 访问 `GET /admin`
打开只读面板，查看每个用户的注册时间、令牌数、便签数量与附件占用
（不支持 `?token=` 查询串：查询串会进入代理日志与浏览器历史）：

```bash
FREENOTE_ADMIN_TOKEN=请改成长随机串 python run.py
curl -H "Authorization: Bearer 请改成长随机串" http://127.0.0.1:8000/admin
```

未设置该环境变量时 `/admin` 返回 404（默认不启用）。

## 部署到 Linux 服务器

```bash
# 1. 上传代码
scp -r server root@<服务器>:/opt/freenote-server

# 2. 安装依赖
cd /opt/freenote-server
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt

# 3. 安装 systemd 服务
cp freenote-server.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now freenote-server

# 4. 检查
curl http://127.0.0.1:8000/api/health
```

`python run.py` 默认监听 `0.0.0.0:8000`（可用 `--host` 调整）；systemd 部署时 unit 文件固定绑定 `127.0.0.1:8000`。对外建议用 Nginx/Caddy 反向代理并开启 HTTPS：

```nginx
location / {
    proxy_pass http://127.0.0.1:8000;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
```

经反向代理部署时，同时设置 `FREENOTE_TRUSTED_PROXIES=127.0.0.1,::1`（按实际代理地址调整），
否则限流会按代理地址聚合而非真实客户端 IP。

### 原生 HTTPS（无反向代理时）

```bash
# 自签名证书（内网/个人部署）
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -keyout key.pem -out cert.pem -subj "/CN=freenote.example.com"

# 启动（两个参数必须同时提供）
python run.py --port 8000 --ssl-certfile cert.pem --ssl-keyfile key.pem
```

客户端使用 `https://<host>:8000` 地址。自签名证书无法通过系统校验：
Windows 端在「设置 → 服务器同步」勾选「跳过 HTTPS 证书校验」，
Android 端在同步设置打开「允许自签名证书」。传输全程加密；
跳过校验仅省去身份验证，公网环境建议改用受信证书（Let's Encrypt 等）。

## 安全说明

- 密码使用 PBKDF2-HMAC-SHA256（600000 次迭代 + 随机盐）存储；
  存储格式自带迭代数，旧哈希（低迭代）仍可正常校验；
- 令牌为随机 32 字节，服务端只保存其 SHA-256；新令牌默认 90 天过期
  （到期后自动清理并要求重新登录），退出登录、修改密码即撤销；
  旧版本签发的无过期时间令牌保持有效，直到用户下次登录换取新令牌；
- 登录/注册/改密/注销接口有进程内滑动窗口限流（每 IP+用户名 5 分钟 10 次），
  单实例部署足够；多实例部署时需在反向代理层另行限流；
  限流键只在 `FREENOTE_TRUSTED_PROXIES` 覆盖的来源上采信转发头（防止伪造 IP 绕过）；
- 附件（图片）与普通便签一样以明文存储，且不解析内容；
  单用户配额 512 MB，按内容寻址天然去重；
- 建议仅通过 HTTPS 暴露；锁定便签由客户端加密，服务端只能看到密文；
- 普通便签在服务端为明文 JSON（与客户端本地存储一致），请限制
  `data/` 目录权限（建议仅服务账号可读）并避免把数据库放在共享目录；
- 数据库文件 `data/freenote.db` 建议定期备份，备份件按敏感数据处理。

## 测试

```bash
cd server
python -m unittest tests.test_server -v
```
