# user · 用户模块

> ✅ **状态：已实现并端到端验证过，可以直接联调。**

## 接口总览

所有路径都要加 `/api` 前缀（见 [01-通用约定](01-通用约定.md)）。

| 方法 | 路径 | 说明 | 需要登录 |
|---|---|---|---|
| POST | `/user/register` | 注册（密码注册 / 邮箱验证码注册） | 否 |
| POST | `/user/sendCode` | 发送邮箱注册验证码 | 否 |
| POST | `/user/login` | 登录 | 否 |
| GET | `/user/current` | 获取当前登录用户 | **是** |
| POST | `/user/update` | 改昵称 / 头像 | **是** |
| POST | `/user/updatePassword` | 改密码 | **是** |
| POST | `/user/logout` | 注销 | **是** |

## 数据结构

### `UserVO`（用户信息，脱敏）

```ts
interface UserVO {
  id: string;          // ⚠️ 字符串，不是数字（见 01-通用约定）
  username: string;    // 用户名
  nickname: string;    // 昵称
  avatar: string;      // 头像 URL，为空时后端会返回一个默认头像地址
  userRole: string;    // 角色："user" | "admin" | "ban"
  createTime: string;  // 注册时间，ISO-8601 字符串（格式待实测，见 01-通用约定）
}
```

**⚠️ 返回里没有 `email`，也没有 `password`。** 这是刻意的脱敏——用户名和邮箱不在同一个接口里暴露。

**`avatar` 不用自己做兜底**：后端在头像为空时会根据用户 id 生成一个默认头像 URL 返回，前端直接展示即可。

---

## 1. 注册

```
POST /api/user/register
```

**支持两种注册方式**，由请求体里的 `registerType` 决定：

| `registerType` | 方式 | 需要的字段 |
|---|---|---|
| `"password"`（**默认**，不传就是这个） | 用户名 + 密码 | `username`、`password`、`checkPassword` |
| `"email"` | 邮箱 + 验证码 | `email`、`code`、`password` |

### 请求体

| 字段 | 类型 | 说明 |
|---|---|---|
| `registerType` | string | `"password"` 或 `"email"`，不传按 `"password"` 处理 |
| `username` | string | 用户名，**密码注册必填** |
| `password` | string | 密码，两种方式都必填 |
| `checkPassword` | string | 确认密码，**密码注册必填** |
| `nickname` | string | 昵称，可选 |
| `email` | string | 邮箱，**邮箱注册必填** |
| `code` | string | 邮箱验证码，**邮箱注册必填** |

### 密码注册的校验规则

| 规则 | 不满足时的 `message` |
|---|---|
| `username`、`password`、`checkPassword` 都非空 | `参数为空` |
| `username` 长度 ≥ 4 | `用户名过短` |
| `password` 和 `checkPassword` 长度都 ≥ 8 | `用户密码过短` |
| `username` 不含特殊字符（`!@#$%^&*` 等） | `用户名不能包含特殊字符` |
| 两次密码一致 | `两次输入的密码不一致` |
| `username` 未被占用 | `用户名已存在` |

`nickname` 不传时，后端会把 `username` 当作昵称。

### 邮箱注册的校验规则

| 规则 | 不满足时的 `message` |
|---|---|
| `email`、`code`、`password` 都非空 | `邮箱、验证码或密码不能为空` |
| `email` 含 `@` | `邮箱格式不正确` |
| `password` 长度 ≥ 8 | `密码长度不能小于8位` |
| 验证码未过期（5 分钟内） | `验证码已过期，请重新获取` |
| 验证码正确 | `验证码错误` |
| `email` 未被注册 | `该邮箱已注册` |

**⚠️ 邮箱注册时 `username` 是后端自动生成的**——取邮箱 `@` 前面的部分。所以 `zhangsan@qq.com` 注册后，用户名是 `zhangsan`，昵称也是 `zhangsan`，**前端不需要传 `username`**。

**⚠️ 由此产生的一个边界情况**：如果已经有人用 `zhangsan` 这个用户名注册过，那么 `zhangsan@qq.com` 注册会失败，`message` 是 `用户注册失败`。这个提示不太友好——遇到时可以提示用户「该邮箱前缀已被占用，请换邮箱」。

### 响应

```json
{ "code": 0, "data": "1844674407370955161", "message": "ok" }
```

`data` 是**新用户的 id（字符串）**。

### ⚠️ 注册接口的错误码全是 `40000`

后端这个接口把所有异常都收敛成了 `40000`，**真正的原因在 `message` 里**。所以：

```js
if (body.code !== 0) {
  // ✅ 必须展示 message，否则用户不知道哪错了
  showFormError(body.message || '注册失败，请稍后重试');
}
```

### 示例

```js
// 密码注册
await fetch('/api/user/register', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  credentials: 'include',
  body: JSON.stringify({
    registerType: 'password',
    username: 'zhangsan',
    password: '12345678',
    checkPassword: '12345678',
    nickname: '张三',
  }),
});

// 邮箱注册（code 从用户邮箱里拿，或开发环境从后端日志里捞）
await fetch('/api/user/register', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  credentials: 'include',
  body: JSON.stringify({
    registerType: 'email',
    email: 'zhangsan@qq.com',
    code: '123456',
    password: '12345678',
  }),
});
```

---

## 2. 发送邮箱验证码

```
POST /api/user/sendCode
```

### 请求体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `email` | string | 是 | 邮箱地址，必须含 `@` |

### 响应

```json
{ "code": 0, "data": true, "message": "ok" }
```

### 行为说明

- 生成 6 位数字验证码，**存入 Redis，5 分钟有效**（覆盖同一个邮箱的上一个验证码）
- 尝试发送邮件到该邮箱，标题「智能星知识助手 注册验证码」
- 校验不过时：`code: 40000`，`message: "邮箱格式不正确"`

### ⚠️ 开发环境不会真的收到邮件

如果后端没配 SMTP（本地开发就是这样），发信会失败，但**接口仍然返回成功**。验证码会被打到**后端控制台日志**里：

```
邮件发送失败(可能是未配置SMTP)，验证码已写入Redis: email=zhangsan@qq.com, code=123456
```

**前端联调时**：调完这个接口，去后端控制台拿那串 `code`，填进注册表单。

**这个设计对前端的实际影响**：不能靠「接口成功」来判断用户能不能收到邮件。如果要做「验证码已发送到您的邮箱」这种提示，直接显示即可，但真伪在开发环境是不成立的。

---

## 3. 登录

```
POST /api/user/login
```

### 请求体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `username` | string | 是 | 用户名（长度需 ≥ 4） |
| `password` | string | 是 | 密码（长度需 ≥ 8） |

**注意这是用户名登录，不是邮箱登录。** 邮箱注册的用户，用户名是邮箱 `@` 前面的部分。

### 响应

```json
{
  "code": 0,
  "data": {
    "id": "1844674407370955161",
    "username": "zhangsan",
    "nickname": "张三",
    "avatar": "https://...",
    "userRole": "user",
    "createTime": "2026-09-21T10:30:00"
  },
  "message": "ok"
}
```

**登录成功后，响应里会带 `Set-Cookie`。** 之后的请求必须带上这个 cookie（见 [01-通用约定](01-通用约定.md) 的鉴权一节）。

### 失败情况

全部是 `code: 40000`，`message` 有三种：

| 场景 | `message` |
|---|---|
| 用户名或密码为空 / 长度不够 | `参数为空` / `用户名错误` / `密码错误` |
| 用户不存在，或密码不匹配 | `用户不存在或密码错误` |

**注意「用户不存在」和「密码错误」被合并成了同一条提示**——这是有意的安全设计（避免攻击者通过错误信息试探出哪些用户名存在）。

日志里能看到具体原因（`user login failed, username cannot match password`），但接口不返回。

---

## 4. 获取当前登录用户

```
GET /api/user/current
```

无请求参数。

### 响应

成功：`data` 是 `UserVO`（结构同登录）。

未登录时：**HTTP 200**，响应体是

```json
{ "code": 40100, "data": null, "message": "未登录" }
```

### 用途

**这是前端判断「用户是否登录」的标准方式**，通常用在：

- 应用启动时（路由守卫里）检查登录态
- 刷新页面后恢复用户信息
- 定时校验会话是否还有效

```js
async function checkLogin() {
  const res = await fetch('/api/user/current', { credentials: 'include' });
  const body = await res.json();
  if (body.code === 40100) {
    // 未登录，跳登录页
    return null;
  }
  return body.data;   // UserVO
}
```

**⚠️ 会话过期后这个接口返回 `40100`**，前端要有统一的拦截处理（比如 axios 响应拦截器里判断 `code === 40100` 就跳登录页）。

---

## 5. 更新用户信息

```
POST /api/user/update
```

**只能改自己的信息。** 用户的 id 从会话里取，**请求体里的任何 id 都会被忽略**——这是后端的防越权设计（不用前端操心，但别指望传 id 能改别人）。

### 请求体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `nickname` | string | 否 | 新昵称 |
| `avatar` | string | 否 | 新头像 URL |

**两个字段都是「传了才改」**：不传或传空字符串，对应字段保持不变。所以只改昵称时不用把 avatar 也带上。

### 响应

`data` 是**更新后的** `UserVO`。

### 失败情况

| 场景 | `code` | `message` |
|---|---|---|
| 未登录 | `40100` | `未登录` |
| 更新失败 | `50001` | `更新失败` |
| 请求体为 `null` | `40000` | `请求参数错误` |

**成功后后端会刷新会话里的用户数据**，所以不需要重新登录。

### 关于头像上传

**这个接口只接受头像的 URL 字符串，不接受文件上传。** 如果需要用户上传本地图片：

1. 前端先调（待实现的）文件上传接口拿到 URL
2. 再用这个 URL 调 `/user/update`

文件上传接口目前**还没有**，见 [50-knowledge-知识库管理](50-knowledge-知识库管理.md) 里的上传能力（那个是知识库文档的上传，头像上传需要单独一个接口，尚未规划）。

**所以当前最简做法**：前端提供一组预置头像让用户选，选中的 URL 直接传给这个接口。

---

## 6. 修改密码

```
POST /api/user/updatePassword
```

### 请求体

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `oldPassword` | string | 是 | 旧密码 |
| `newPassword` | string | 是 | 新密码，**长度 ≥ 8** |

### 响应

```json
{ "code": 0, "data": true, "message": "ok" }
```

### 失败情况

| 场景 | `code` | `message` |
|---|---|---|
| 任一字段为空 | `40000` | `旧密码和新密码不能为空` |
| 新密码短于 8 位 | `40000` | `新密码长度不能小于8位` |
| 旧密码不对 | `40000` | `旧密码错误` |
| 更新失败 | `50001` | `修改密码失败` |

### ⚠️ 改完密码**不需要重新登录**

后端会保持当前会话有效（主动刷新了会话里的用户数据）。

**但这个行为只保证当前这个浏览器的会话**——其他设备上的登录状态不受影响，也不会被强制下线。如果产品需要「改密码后踢掉其他设备」，那是**尚未实现**的功能。

---

## 7. 注销

```
POST /api/user/logout
```

无请求体。

### 响应

```json
{ "code": 0, "data": true, "message": "ok" }
```

### 失败情况

**⚠️ 这里有个和其他接口不一致的地方**：未登录时调注销，返回的是

```json
{ "code": 40000, "data": null, "message": "未登录" }
```

**是 `40000`，不是 `40100`。** 这是后端的历史遗留（注销接口的异常处理没走 `NOT_LOGIN_ERROR` 那条路）。

**前端建议**：注销时**不要根据返回码判断，直接当成成功处理**——无论返回什么，都把本地的用户状态清掉并跳登录页。因为「本来就没登录」和「注销成功」对用户来说是一样的结果。

```js
async function logout() {
  try {
    await api.post('/api/user/logout');
  } finally {
    // 无论成功失败，都清本地状态（这才是用户的预期）
    clearLocalUserState();
    router.push('/login');
  }
}
```

---

## 完整的登录流程示例

```js
// 1. 登录
const loginRes = await fetch('/api/user/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  credentials: 'include',              // ★ 必须，否则 cookie 不会被保存
  body: JSON.stringify({ username, password }),
});
const loginBody = await loginRes.json();
if (loginBody.code !== 0) {
  showFormError(loginBody.message);
  return;
}
// loginBody.data 是 UserVO，可以直接用于展示

// 2. 后续请求自动带 cookie（同源或配了代理的情况下）
const curRes = await fetch('/api/user/current', { credentials: 'include' });
const curBody = await curRes.json();
if (curBody.code === 0) {
  // curBody.data 是 UserVO
}
```

## 前端类型定义（可直接复制）

```ts
export interface UserVO {
  id: string;
  username: string;
  nickname: string;
  avatar: string;
  userRole: 'user' | 'admin' | 'ban';
  createTime: string;
}

export interface UserRegisterRequest {
  registerType?: 'password' | 'email';
  username?: string;        // password 方式必填
  password: string;
  checkPassword?: string;   // password 方式必填
  nickname?: string;
  email?: string;           // email 方式必填
  code?: string;            // email 方式必填
}

export interface UserLoginRequest {
  username: string;
  password: string;
}

export interface UserUpdateRequest {
  nickname?: string;
  avatar?: string;
}

export interface UserPasswordUpdateRequest {
  oldPassword: string;
  newPassword: string;
}
```

## 这个模块的已知限制

| 限制 | 说明 |
|---|---|
| 没有「忘记密码」 | 只能通过已登录状态改密码 |
| 没有头像上传接口 | 只能传 URL |
| 用户名不能修改 | 只有昵称和头像可改 |
| 邮箱注册的用户名由邮箱前缀生成 | 可能与已有用户名冲突，冲突时提示不友好 |
| 没有登录失败次数限制 | 可以无限次尝试密码 |
| 没有「踢下线」机制 | 改密码不影响其他设备的登录态 |
| 没有刷新令牌 / 无感续期 | 服务端 session 30 天过期，过期后需重新登录 |
