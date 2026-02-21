# JediTerm Sample（SSH）

本 sample app 会在启动时从 app 内部存储读取 `.env`，并使用 `id_rsa`（可选）连接 SSH。

## 推送配置与私钥（adb）

```powershell
# 先把 .env 和 id_rsa 推送到 sample app 的内部存储
adb push .env /data/local/tmp/.env
adb push id_rsa /data/local/tmp/id_rsa
adb shell run-as com.lemonhall.jediterm.sample cp /data/local/tmp/.env /data/data/com.lemonhall.jediterm.sample/files/.env
adb shell run-as com.lemonhall.jediterm.sample cp /data/local/tmp/id_rsa /data/data/com.lemonhall.jediterm.sample/files/id_rsa
adb shell run-as com.lemonhall.jediterm.sample chmod 600 /data/data/com.lemonhall.jediterm.sample/files/id_rsa
```

`.env` 支持字段：
- `SSH_HOST`（默认 `192.168.50.149`）
- `SSH_PORT`（默认 `22`）
- `SSH_USER`（默认 `lemonhall`）
- `SSH_PASSWORD`（可选）
- `SSH_PRIVATE_KEY_PATH`（相对 `files/`，默认 `id_rsa`）
- `SSH_PRIVATE_KEY_PASSPHRASE`（可选）

