# faceai-server 部署指南

## 概述

`faceai-server` 是基于 `face-api.js` + `TensorFlow.js Node` 的本地人脸识别 HTTP 服务，默认端口 **3456**。

**生产 URL**：`http://card-test.quyohui.com/faceapi/`（Nginx 反向代理 → `127.0.0.1:3456`）

| API | 方法 | 说明 |
|---|---|---|
| `/api/face/search` | POST | 1:N 人脸搜索 |
| `/api/face/enroll` | POST | 单条录入人脸特征 |
| `/api/face/enroll/batch` | POST | 批量录入人脸特征 |
| `/api/face/enroll/:employeeId` | DELETE | 删除指定员工 |
| `/api/face/clear` | DELETE | 清空人脸库 |
| `/api/face/db/status` | GET | 查看人脸库详情 |
| `/api/face/db/export` | GET | 导出人脸库（JSON） |
| `/api/face/db/import` | POST | 导入人脸库（JSON） |
| `/health` | GET | 健康检查 |

人脸库保存在**内存**中，服务重启后需重新录入。可通过 **导出/导入** 接口实现持久化，避免每次重启后逐条重录。

## 一、CentOS 7 环境准备

### 1. 安装系统依赖（编译 native addon 必需）

```bash
# 修复 yum 仓库问题（如有 pritunl 404）
yum-config-manager --disable pritunl
# 如果没有 yum-config-manager，先装：
yum install -y yum-utils
yum-config-manager --disable pritunl

# 安装编译工具和 canvas 依赖
yum groupinstall -y "Development Tools"
yum install -y gcc-c++ make cairo-devel pango-devel libjpeg-turbo-devel giflib-devel
```

### 2. 校验

```bash
gcc --version
pkg-config --cflags cairo 2>/dev/null && echo "cairo OK" || echo "cairo MISSING"
```

## 二、宝塔面板部署（CentOS 7）

### 方法 A：宝塔 Node 项目管理器（推荐）

1. **软件商店** → 搜索并安装 **「Node.js版本管理器」**

2. 在 Node 管理器中安装 **Node 14.x**（与 `@tensorflow/tfjs-node@1.7.4` 兼容）

3. **文件** → 上传项目源码到目标目录（如 `/opt/card_server/faceai-server`）
   > **只上传源码文件**（`server.js`、`package.json`、`download-models.js`），不要上传本地 Mac 的 `node_modules` 和 `package-lock.json`。Mac 编译的 `.node` 原生模块在 Linux 上无法使用。

4. 手动执行安装（宝塔自动 `npm install` 可能因 PATH 问题失败）：

   ```bash
   # 宝塔 Node 不在默认 PATH 中，必须先设置
   export PATH="/www/server/nodejs/v14.20.1/bin:$PATH"
   cd /opt/card_server/faceai-server

   # 删掉可能从 Mac 带过来的平台相关文件
   rm -rf node_modules package-lock.json

   # 在服务器本地重新编译原生模块
   npm install --build-from-source

   # 下载模型
   node download-models.js
   ```

5. **Node项目** → 添加 Node 项目：

   | 配置项 | 值 |
   |---|---|
   | 项目目录 | `/opt/card_server/faceai-server` |
   | 启动选项 | `server.js` |
   | Node 版本 | 14.x |
   | 项目端口 | `3456` |
   | 运行用户 | `root` |

6. **网站** → 添加反向代理：
   - 代理路径：`/faceapi/`
   - 目标 URL：`http://127.0.0.1:3456`

### 方法 B：PM2 手动管理

```bash
cd /opt/card_server/faceai-server

export PATH="/www/server/nodejs/v14.20.1/bin:$PATH"
rm -rf node_modules package-lock.json
npm install --build-from-source --registry=https://registry.npmmirror.com
node download-models.js

npm install -g pm2
pm2 start server.js --name faceai-server
pm2 save
pm2 startup
```

## 三、Mac 本地开发

```bash
cd uniapp/scripts/faceai-server
npm install
npm run download-models
npm start
```

> **已知问题**：路径含空格（如 `/Volumes/Macintosh HD - Data/...`）会导致 `node-gyp` 编译失败。临时方案：将项目复制到无空格路径（如 `/tmp/faceai-build`）执行 `npm install`，再把 `node_modules` 拷回原目录。

## 四、验证

```bash
# 健康检查
curl http://127.0.0.1:3456/health
# → {"status":"ok","modelsLoaded":true,"persons":0,"totalDescriptors":0}

# 单个录入
# curl -X POST http://127.0.0.1:3456/api/face/enroll \
#   -H "Content-Type: application/json" \
#   -d '{"employeeId":"E001","image":"data:image/jpeg;base64,..."}'

# 批量录入
# curl -X POST http://127.0.0.1:3456/api/face/enroll/batch \
#   -H "Content-Type: application/json" \
#   -d '{"items":[
#     {"employeeId":"E001","image":"data:image/jpeg;base64,..."},
#     {"employeeId":"E002","image":"data:image/jpeg;base64,..."}
#   ]}'

# 搜索
# curl -X POST http://127.0.0.1:3456/api/face/search \
#   -H "Content-Type: application/json" \
#   -d '{"image":"data:image/jpeg;base64,..."}'

# 查看人脸库
curl http://127.0.0.1:3456/api/face/db/status
# → {"persons":2,"totalDescriptors":2,"list":[{"employeeId":"E001","photoCount":1},...]}

# 导出人脸库
curl http://127.0.0.1:3456/api/face/db/export > face_db_backup.json

# 导入人脸库
# curl -X POST http://127.0.0.1:3456/api/face/db/import \
#   -H "Content-Type: application/json" \
#   -d @face_db_backup.json

# 删除指定员工
# curl -X DELETE http://127.0.0.1:3456/api/face/enroll/E001

# 清空人脸库
# curl -X DELETE http://127.0.0.1:3456/api/face/clear
```

## 五、持久化工作流

由于人脸库仅存内存，服务重启后丢失。推荐的持久化流程：

```bash
# === 初次部署 ===
# 1. 启动服务
# 2. 从业务系统拉取员工人脸照片，批量录入
curl -X POST http://127.0.0.1:3456/api/face/enroll/batch \
  -H "Content-Type: application/json" \
  -d '{"items":[...]}'

# 3. 导出当前人脸库到本地文件
curl http://127.0.0.1:3456/api/face/db/export > /opt/card_server/face_db.json

# === 服务重启后恢复 ===
# 1. 检查之前的备份
ls -la /opt/card_server/face_db.json

# 2. 导入备份（如需清空旧数据，先调 clear）
curl -X POST http://127.0.0.1:3456/api/face/db/import \
  -H "Content-Type: application/json" \
  -d @/opt/card_server/face_db.json

# === 定期备份 ===
# 可在 cron 中定时导出（每小时）
# crontab -e
# 0 * * * * curl -s http://127.0.0.1:3456/api/face/db/export > /opt/card_server/face_db_$(date +\%Y\%m\%d_\%H\%M).json
```

> **注意**：导入不会自动清库（多条导入是追加模式），如需替换请先调用 `DELETE /api/face/clear`。

## 六、常见问题

| 问题 | 原因 | 解决 |
|---|---|---|
| `invalid ELF header` | 从 Mac 直接复制了 `node_modules`，原生模块是 Mach-O 格式，Linux 无法加载 | 删除 `node_modules` 和 `package-lock.json`，在服务器上重新 `npm install --build-from-source` |
| `sh: node: 未找到命令` | 宝塔 Node 不在系统 PATH 中 | 先执行 `export PATH="/www/server/nodejs/v14.20.1/bin:$PATH"` 再运行 npm 命令 |
| `tfjs_binding.node not found` | Node 版本不匹配或未编译 | 使用 Node 14.x，确保在服务器上执行了 `npm install --build-from-source` |
| `canvas.node Module version mismatch` | `canvas` 编译的 Node 版本与运行时不同 | 删除 `node_modules/canvas` 后重新 `npm install` |
| `ERR_DLOPEN_FAILED` | GLIBC 版本过低（CentOS 7 GLIBC 2.17）导致预编译的 TensorFlow C 库无法加载 | 使用 `--build-from-source` 在本地重新编译原生模块 |
| `模型文件缺失` | 未下载模型 | 运行 `node download-models.js` |
| 人脸库为空/重启后丢失 | 数据存储在内存中 | 使用 `export` 接口定期备份，重启后 `import` 恢复（见第五节） |
| 导入后人数不对 | 导入是追加模式，可能已有旧数据 | 导入前先调 `DELETE /api/face/clear` 清库 |
| 批量录入失败部分条目 | 个别图片无人脸或格式错误 | 检查响应 `failed` 数组，确认 `reason` 并重试失败项 |
| CentOS `pritunl` 仓库报错 | repo 已下线 | `yum-config-manager --disable pritunl` |

## 七、Mac → Linux 发布注意事项

> **核心原则**：原生模块（`.node` 文件）与操作系统、CPU 架构、Node 版本强绑定，不能跨平台复用。

| 可上传 | 禁止上传 |
|---|---|
| `server.js`、`package.json`、`download-models.js` | `node_modules/`（含 Mac 编译的 `.node`） |
| `models/` 目录（纯 JSON/二进制模型文件，跨平台通用） | `package-lock.json`（锁定平台相关的依赖） |

每次发布到 Linux 时的标准操作：

```bash
# 1. 只上传源码，不传 node_modules
# 2. 在服务器上
export PATH="/www/server/nodejs/v14.20.1/bin:$PATH"
cd /opt/card_server/faceai-server
rm -rf node_modules package-lock.json
npm install --build-from-source
node download-models.js
```

## 八、安全提示

- `CORS` 已开放为 `*`，生产环境建议限制为实际前端域名
- 人脸数据仅存内存，服务重启即丢失——可通过 `export`/`import` 持久化（见第五节）
- 导出的 `face_db.json` 含人脸特征 base64，需妥善保管
- 建议在反向代理层配置 `client_max_body_size`（图片 base64 可能较大，默认 10MB）
