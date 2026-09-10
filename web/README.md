# TideBid Web

TideBid 的 Vue 3 + TypeScript 前端。阶段 01 已提供注册、登录、账户工作台、404 页面、
认证路由守卫，以及对 Gateway 统一响应的请求和错误处理。

要求 Node.js 24.12+（小于 25）和 pnpm 11：

```powershell
node --version
pnpm --version
pnpm install --frozen-lockfile
pnpm dev
```

开发服务器固定监听 `http://127.0.0.1:5173`，并将同源 `/api/**` 请求代理到
`http://127.0.0.1:9000`。要完成真实注册和登录，需先启动 Gateway、Account Service
及其本地中间件；Vue 组件不会直接访问 9101～9105。

当前路由：

- `/login`：登录并保存演示会话。
- `/register`：创建普通用户及其虚拟钱包。
- `/dashboard`：需要登录，展示当前用户、角色和钱包余额。
- 其他地址：显示 404 页面。

Access Token 仅保存在当前标签页的 `sessionStorage`，关闭标签页后自然清除，收到认证
请求的 401 响应时也会主动清除。这个实现只用于本地作品演示：只要页面发生 XSS，脚本仍
可以读取 `sessionStorage` 中的 Token，因此不能直接照搬到真实生产系统。

质量检查：

```powershell
pnpm lint
pnpm type-check
pnpm test
pnpm build
```

单元测试覆盖会话持久化与过期、路由守卫、API 请求头和 401、Pinia 认证状态、表单边界
以及工作台关键数据。生产构建产物输出到 `web/dist/`，该目录不提交 Git。
