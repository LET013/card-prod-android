# 工作卡柜 uni-app UI

Vue 3 + Vite + uni-app H5 界面，作为 Android 原生 WebView 中的交互层。

## 当前职责

- 页面与弹窗 UI
- 响应式布局
- 页面路由
- 表单和角色权限交互
- 本地 Mock Service
- 本地演示数据与持久化
- 统一 Native Bridge 请求、响应与事件监听

串口、WebSocket、人脸/指纹 SDK、开门和 OTA 的长期生命周期均由 Android 原生层负责，WebView 页面不持有这些连接。

## 运行

```bash
npm ci
npm run dev:h5
```

## 构建

```bash
npm run build:h5
```

构建产物位于：

```text
dist/build/h5
```

项目根目录的 `build.sh` 会完成 H5 构建，并同步到 `app/src/main/assets`。

## Mock 登录密码

- 系统管理员：`111111`
- 运维人员：`222222`
- 开发人员：`333333`

仅用于当前 UI/桥接联调。

## Native Bridge

前端统一发送：

```js
window.android.postMessage(JSON.stringify({
  requestId: 'web-uuid',
  action: 'settings.load',
  payload: {}
}))
```

原生统一调用：

```js
window.NativeBridge.receive(message)
```

浏览器预览时，Service Provider 自动使用本地 Mock 实现。
