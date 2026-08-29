---
name: http-api-integration
description: "本项目 HTTP API 接入与封装代码规范。当需要新增 REST API 接口调用、创建 API 服务类、定义 HTTP 请求/响应实体、或对接后端 HTTP 接口时使用此 skill。触发场景包括：新增业务模块需要调用后端接口、创建新的 ApiService 子类、定义新的 Request/Response 实体、或对接接口文档中的新端点。"
---

# HTTP API 接入与封装规范

本规范基于项目中已有的 `BaseApiService` → `DeviceApiService` 分层实践，
定义接入新 HTTP API 的标准化步骤和代码模板。

---

## 1. 架构分层

```
调用方（DeviceBootstrapManager / 业务模块）
  → DeviceApiService (类型化方法，返回强类型 Response)
    → BaseApiService.apiGet / apiPost (统一解包 + 路径拼接)
      → HttpClientManager (OkHttp + Token 注入 + 超时配置)
        → 后端 REST API
```

- **请求/响应实体**：定义在 `core/entity/http/`，命名遵循 `{资源}{动作}Request` / `{资源}{动作}Response`。
- **API 服务类**：继承 `BaseApiService`，构造函数接收 `HttpClientManager`。
- **调用方**：通过 `DeviceBootstrapManager.getHttpClient()` 获取已配置的 `HttpClientManager`，再创建 API 服务实例。

---

## 2. 实体定义规范

### 请求实体模板

```java
// core/entity/http/SomeRequest.java
package com.xingyao.card.core.entity.http;

import org.json.JSONException;
import org.json.JSONObject;

public class SomeRequest {
    public final String fieldA;
    public final int fieldB;

    private SomeRequest(Builder builder) {
        this.fieldA = builder.fieldA;
        this.fieldB = builder.fieldB;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("fieldA", fieldA);
        json.put("fieldB", fieldB);
        return json;
    }

    public static class Builder {
        private String fieldA;
        private int fieldB;

        public Builder fieldA(String v) { this.fieldA = v; return this; }
        public Builder fieldB(int v)  { this.fieldB = v; return this; }
        public SomeRequest build() { return new SomeRequest(this); }
    }
}
```

### 响应实体模板

```java
// core/entity/http/SomeResponse.java
package com.xingyao.card.core.entity.http;

import org.json.JSONObject;

public class SomeResponse {
    public final String resultField;
    public final int count;

    private SomeResponse(String resultField, int count) {
        this.resultField = resultField;
        this.count = count;
    }

    public static SomeResponse fromJson(JSONObject data) {
        return new SomeResponse(
            data.optString("resultField", ""),
            data.optInt("count", 0)
        );
    }
}
```

### 实体命名约定

| 场景 | 命名模式 | 示例 |
|------|---------|------|
| 查询请求 | `{Resource}Request` | `QueryEmployeeRequest` |
| 操作请求 | `{Resource}{Action}Request` | `CardCollectRequest` |
| 查询响应 | `{Resource}Response` | `EmployeeListResponse` |
| 操作结果 | `{Resource}{Action}Result` | `CardCollectResult` |

---

## 3. API 服务类模板

```java
// core/http/XxxApiService.java
package com.xingyao.card.core.http;

import com.xingyao.card.core.entity.http.SomeRequest;
import com.xingyao.card.core.entity.http.SomeResponse;

import org.json.JSONException;
import java.io.IOException;

public class XxxApiService extends BaseApiService {

    /**
     * @param http 已配置好 baseUrl、token 的 HttpClientManager
     */
    public XxxApiService(HttpClientManager http) {
        super(http, "/api/v1");   // apiPrefix 取接口文档中的公共前缀
    }

    /**
     * 业务接口说明。对应文档：POST /api/v1/some/path
     */
    public SomeResponse doSomething(SomeRequest req) throws IOException {
        try {
            return SomeResponse.fromJson(apiPost("/some/path", req.toJson()));
        } catch (JSONException e) {
            throw new IOException("doSomething JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 无请求体的 GET 接口。对应文档：GET /api/v1/some/query
     */
    public SomeResponse query() throws IOException {
        try {
            return SomeResponse.fromJson(apiGet("/some/query"));
        } catch (JSONException e) {
            throw new IOException("query JSON 解析失败: " + e.getMessage(), e);
        }
    }
}
```

### 关键规则

1. **必须继承 `BaseApiService`**：构造函数传入 `HttpClientManager` + `apiPrefix`。
2. **使用 `apiGet(path)` / `apiPost(path, body)`**：自动拼接 prefix、发起请求、解包 envelope（校验 `code==200`），返回 `data` 字段 JSONObject。
3. **所有方法只 `throws IOException`**：在方法内部 `catch (JSONException)` 并包装为 `IOException`。
4. **必须标注对应文档路径**：Javadoc 中注明对应接口文档的 `METHOD /path`。
5. **不在此层做业务判断**：只负责 HTTP 调用 + 解析，不判断 `valid`、不修改 CredentialStore 或 Repository。

---

## 4. 后端信封约定

所有后端接口使用统一信封格式：

```json
{
  "msg": "操作成功",
  "code": 200,
  "data": { ... }
}
```

- `BaseApiService.apiGet/apiPost` 内部调用 `ApiResponseUtil.unwrap()`：
  - `code == 200` → 返回 `data` 字段
  - `code != 200` → 抛出 `IOException`，消息取自 `msg`
  - `data == null` → 抛出 `IOException`

---

## 5. Token 管理

- 使用 `DeviceTokenProvider(credentialStore)` 注入 `HttpClientManager.Builder.tokenProvider()`。
- `DeviceTokenProvider` 从 `CredentialStore` 读取 `deviceToken`，401 时自动尝试刷新。
- 无需 token 的场景（如首次注册）不传 `tokenProvider`。

```java
// 带 token（常规业务请求）
HttpClientManager http = new HttpClientManager.Builder()
    .baseUrl(baseUrl)
    .tokenProvider(new DeviceTokenProvider(credentialStore))
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build();

// 不带 token（注册等匿名接口）
HttpClientManager noAuthHttp = new HttpClientManager.Builder()
    .baseUrl(baseUrl)
    .connectTimeout(15, TimeUnit.SECONDS)
    .build();
```

---

## 6. 调用方使用模式

```java
// 方式1：复用 Bootstrap 已配置的 httpClient（推荐）
DeviceApiService api = new DeviceApiService(bootstrapManager.getHttpClient());
// 或
XxxApiService myApi = new XxxApiService(bootstrapManager.getHttpClient());
SomeResponse resp = myApi.doSomething(req);

// 方式2：独立创建
HttpClientManager http = new HttpClientManager.Builder()
    .baseUrl(baseUrl)
    .tokenProvider(new DeviceTokenProvider(credentialStore))
    .build();
XxxApiService myApi = new XxxApiService(http);
```

**禁止事项**：
- 禁止在 API 服务类中引用 UI、Activity、Context、Repository 或业务 Coordinator
- 禁止绕过 `BaseApiService` 直接使用 `HttpClientManager.post/get` + `ApiResponseUtil.unwrap`
- 禁止在 Activity 或 Fragment 中发起 HTTP 调用
- 禁止根据常识或推测补全未在文档中定义的字段或端点

---

## 7. 新增 API 的 Checklist

- [ ] 在 `core/entity/http/` 中定义 Request / Response 实体
- [ ] 实体字段必须能在接口文档中找到对应来源
- [ ] 创建 `XxxApiService extends BaseApiService`，标注 apiPrefix
- [ ] 每个方法 Javadoc 注明对应文档中的 `METHOD /path`
- [ ] `JSONException` 在方法内部 catch 并包装为 `IOException`
- [ ] 在调用方通过 `HttpClientManager` + 对应 Service 发起调用
- [ ] `./gradlew :app:compileDebugJavaWithJavac` 通过
- [ ] 未验证的端点和方法标记 `// 待验证` 注释

---

## 8. 现有参考实现

| 文件 | 说明 |
|------|------|
| `core/http/BaseApiService.java` | 基类：apiGet / apiPost 自动解包 |
| `core/http/ApiResponseUtil.java` | 信封解包工具：code 校验 + data 提取 |
| `core/http/DeviceApiService.java` | 范例：设备注册/激活/验证/配置/登录 |
| `core/http/HttpClientManager.java` | OkHttp 封装：Builder + Token 链 |
| `core/http/DeviceTokenProvider.java` | Token 提供器：基于 CredentialStore |
| `core/entity/http/RegisterRequest.java` | 范例：带 Builder 的请求实体 |
| `core/entity/http/RegisterResponse.java` | 范例：fromJson 响应实体 |
| `core/entity/http/ActivateResponse.java` | 范例：含分支判断的响应实体 |
