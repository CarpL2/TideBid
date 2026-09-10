# TideBid Web

TideBid 的 Vue 3 + TypeScript 前端。当前里程碑只建立 Router、Pinia、Axios、Element Plus、
ESLint、类型检查、Vitest 和 Vite 构建基础；认证页面与工作台随后实现。

要求 Node.js 24.12+（小于 25）和 pnpm 11：

```powershell
node --version
pnpm --version
pnpm install --frozen-lockfile
pnpm dev
```

质量检查：

```powershell
pnpm lint
pnpm type-check
pnpm test
pnpm build
```

开发服务器固定监听 `http://127.0.0.1:5173`。业务请求统一使用同源 `/api` 前缀；到
Gateway 9000 的开发代理和认证拦截器将在下一个前端里程碑完成。
