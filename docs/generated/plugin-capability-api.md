<!-- Generated from plugin-tooling/contracts/capability-contracts.json. Do not edit. -->

# BJTU Plugin Capability API

Contract profile: `contract_v1` · Protocol: `v2` · Runtime floor: `2`

SDK calls use camelCase. Read methods return `{ data, meta }`; `meta` contains `syncedAt`, `source`, `coverage`, and `fromCache`.

| Capability | Stability | Permission | Confirmation | Timeout | Methods |
| --- | --- | --- | --- | ---: | --- |
| `runtime.lifecycle@1` | stable | none | none | 5000 ms | `handshake`, `ready`, `close` |
| `configuration.read@1` | stable | `app.configuration.read` | none | 5000 ms | `get` |
| `remote.frame@1` | stable | `remote.frame` | none | 0 ms | declaration only |
| `navigation.external@1` | stable | `navigation.external` | userGesture | 5000 ms | `open` |
| `identity.profile@1` | stable | `identity.profile.read` | none | 15000 ms | `getProfile` |
| `academic.timetable@1` | stable | `academic.timetable.read` | none | 15000 ms | `getTimetable` |
| `academic.scores@1` | stable | `academic.scores.read` | none | 15000 ms | `getScores`, `getHistoryScores` |
| `academic.exams@1` | stable | `academic.exams.read` | none | 15000 ms | `getExams` |
| `academic.calendar@1` | stable | `academic.calendar.read` | none | 15000 ms | `getCalendar` |
| `academic.progress@1` | stable | `academic.progress.read` | none | 15000 ms | `getProgress` |
| `academic.homework@1` | stable | `academic.homework.read` | none | 15000 ms | `getHomework` |
| `academic.resources@1` | stable | `academic.course_resources.read` | none | 15000 ms | `getCourseResources` |
| `mail.read@1` | stable | `mail.read` | none | 15000 ms | `listFolders`, `listMessages`, `getMessage` |
| `campus.request@1` | stable | `campus.request` | none | 15000 ms | `request` |
| `network.request@1` | beta | `network.request` | none | 15000 ms | `request` |
| `storage.kv@2` | beta | `storage.kv` | none | 10000 ms | `get`, `set`, `remove`, `keys`, `usage`, `batch`, `transaction`, `export`, `import` |
| `storage.blob@1` | beta | `storage.blob` | none | 60000 ms | `put`, `getInfo`, `delete` |
| `cache.resource@1` | beta | `cache.resource` | none | 60000 ms | `put`, `promote`, `deleteHandle`, `match`, `delete`, `pin`, `usage` |
| `android.accessibility.events@1` | beta | `android.accessibility.events` | none | 5000 ms | `getStatus`, `subscribe`, `unsubscribe`, `listSubscriptions` |
| `android.accessibility.nodes@1` | beta | `android.accessibility.nodes` | none | 5000 ms | `getRoot`, `find`, `get` |
| `android.accessibility.actions@1` | beta | `android.accessibility.actions` | none | 10000 ms | `performNode`, `performGlobal`, `dispatchGesture` |
| `android.packages.read@1` | beta | `android.packages.read` | none | 10000 ms | `list`, `get`, `resolveIntent` |
| `android.settings.open@1` | beta | `android.settings.open` | none | 5000 ms | `open` |
| `android.device.info@1` | beta | `android.device.info` | none | 5000 ms | `getInfo` |
| `android.network.status@1` | beta | `android.network.status` | none | 5000 ms | `getStatus`, `subscribe`, `unsubscribe` |
| `android.battery.status@1` | beta | `android.battery.status` | none | 5000 ms | `getStatus`, `subscribe`, `unsubscribe` |
| `android.haptics.perform@1` | beta | `android.haptics.perform` | none | 5000 ms | `perform` |
| `android.files.pick@1` | beta | `android.files.pick` | none | 120000 ms | `pick` |
| `android.files.save@1` | beta | `android.files.save` | none | 120000 ms | `save` |
| `android.media.pick@1` | beta | `android.media.pick` | none | 120000 ms | `pick` |
| `android.share.open@1` | beta | `android.share.open` | none | 30000 ms | `open` |
| `android.notifications.post@1` | beta | `android.notifications.post` | none | 10000 ms | `getStatus`, `show`, `schedule`, `cancel` |
| `android.location.read@1` | beta | `android.location.read` | none | 60000 ms | `getStatus`, `getCurrent` |
| `android.calendar.read@1` | beta | `android.calendar.read` | none | 10000 ms | `list` |
| `android.calendar.write@1` | beta | `android.calendar.write` | none | 10000 ms | `create`, `update`, `delete` |
| `android.camera.capture@1` | beta | `android.camera.capture` | none | 120000 ms | `capturePhoto` |
| `android.audio.record@1` | beta | `android.audio.record` | none | 30000 ms | `start`, `stop` |
| `android.sensors.read@1` | beta | `android.sensors.read` | none | 5000 ms | `list`, `subscribe`, `unsubscribe` |
| `android.biometric.verify@1` | beta | `android.biometric.verify` | none | 60000 ms | `getStatus`, `verify` |
| `academic.userCourses.command@1` | beta | `academic.user_courses.write` | eachCall | 15000 ms | `save`, `delete` |
| `academic.homework.submit@1` | beta | `academic.homework.submit` | eachCall | 60000 ms | `submit` |
| `mail.send@1` | beta | `mail.send` | eachCall | 60000 ms | `send` |
| `android.app.autoStart@1` | beta | `android.app.autoStart` | none | 0 ms | declaration only |
| `android.session.keepAlive@1` | beta | `android.session.keepAlive` | none | 10000 ms | `acquire`, `renew`, `release`, `getStatus` |

## runtime.lifecycle@1

Stability: **stable** · confirmation: **none** · idempotency: **none**

Events: `resume`, `pause`, `theme`, `resize`, `network`, `back` (acknowledged).

### handshake

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "sdkVersion"
  ],
  "properties": {
    "sdkVersion": {
      "type": "string"
    }
  }
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "protocolVersion",
    "contractProfile",
    "runtimeFloor",
    "availableCapabilities",
    "binaryTransports"
  ],
  "properties": {
    "protocolVersion": {
      "type": "integer"
    },
    "contractProfile": {
      "type": "string"
    },
    "runtimeFloor": {
      "type": "integer"
    },
    "availableCapabilities": {
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "binaryTransports": {
      "type": "array",
      "uniqueItems": true,
      "items": {
        "type": "string",
        "enum": [
          "arraybuffer",
          "base64url-chunks-v1"
        ]
      }
    },
    "preferredBinaryTransport": {
      "type": "string",
      "enum": [
        "arraybuffer",
        "base64url-chunks-v1"
      ]
    }
  }
}
```

Errors: `request_timeout`, `capability_unavailable`.

### ready

Request schema:

```json
{
  "$ref": "#/schemas/empty"
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "ready"
  ],
  "properties": {
    "ready": {
      "type": "boolean"
    }
  }
}
```

Errors: `request_timeout`.

### close

Request schema:

```json
{
  "$ref": "#/schemas/empty"
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "closed"
  ],
  "properties": {
    "closed": {
      "type": "boolean"
    }
  }
}
```

Errors: `request_timeout`.

## configuration.read@1

读取插件配置：读取用户为当前插件填写的已声明配置项。

Stability: **stable** · confirmation: **none** · idempotency: **none**

### get

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "key"
  ],
  "properties": {
    "key": {
      "type": "string"
    }
  }
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "value"
  ],
  "properties": {
    "value": {
      "type": [
        "string",
        "null"
      ]
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `invalid_request`.

## remote.frame@1

嵌入远程页面：允许在无原生桥的 sandbox iframe 中加载已声明来源。

Stability: **stable** · confirmation: **none** · idempotency: **none**

## navigation.external@1

打开外部链接：通过用户手势在系统浏览器打开已声明来源。

Stability: **stable** · confirmation: **userGesture** · idempotency: **none**

### open

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "url"
  ],
  "properties": {
    "url": {
      "type": "string"
    }
  }
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "opened"
  ],
  "properties": {
    "opened": {
      "type": "boolean"
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `origin_denied`, `user_cancelled`.

## identity.profile@1

读取个人身份信息：读取姓名、学号、学院、专业和邮箱等本地同步资料。

Stability: **stable** · confirmation: **none** · idempotency: **none**

### getProfile

Request schema:

```json
{
  "$ref": "#/schemas/readOptions"
}
```

Response schema:

```json
{
  "$ref": "#/schemas/studentProfileRead"
}
```

Errors: `permission_denied`, `request_timeout`, `network_timeout`.

## academic.timetable@1

读取课表：读取本地或校园系统中的课程表。

Stability: **stable** · confirmation: **none** · idempotency: **none**

### getTimetable

Request schema:

```json
{
  "$ref": "#/schemas/readOptions"
}
```

Response schema:

```json
{
  "$ref": "#/schemas/timetableRead"
}
```

Errors: `permission_denied`, `request_timeout`, `network_timeout`.

## academic.scores@1

读取成绩：读取当前与历史成绩。

Stability: **stable** · confirmation: **none** · idempotency: **none**

### getScores

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "term": {
      "type": "string"
    },
    "courseType": {
      "type": "string"
    },
    "forceRefresh": {
      "type": "boolean"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/scoreRead"
}
```

Errors: `permission_denied`, `request_timeout`, `network_timeout`.

### getHistoryScores

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "term": {
      "type": "string"
    },
    "forceRefresh": {
      "type": "boolean"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/scoreRead"
}
```

Errors: `permission_denied`, `request_timeout`, `network_timeout`.

## academic.exams@1

读取考试安排：读取考试时间与地点。

Stability: **stable** · confirmation: **none** · idempotency: **none**

### getExams

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "term": {
      "type": "string"
    },
    "forceRefresh": {
      "type": "boolean"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/examRead"
}
```

Errors: `permission_denied`, `request_timeout`, `network_timeout`.

## academic.calendar@1

读取校历：读取校历与教学周信息。

Stability: **stable** · confirmation: **none** · idempotency: **none**

### getCalendar

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "month": {
      "type": "string"
    },
    "forceRefresh": {
      "type": "boolean"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/calendarRead"
}
```

Errors: `permission_denied`, `request_timeout`, `network_timeout`.

## academic.progress@1

读取学业进度：读取培养方案完成情况。

Stability: **stable** · confirmation: **none** · idempotency: **none**

### getProgress

Request schema:

```json
{
  "$ref": "#/schemas/readOptions"
}
```

Response schema:

```json
{
  "$ref": "#/schemas/academicProgressRead"
}
```

Errors: `permission_denied`, `request_timeout`, `network_timeout`.

## academic.homework@1

读取作业：读取作业列表与状态。

Stability: **stable** · confirmation: **none** · idempotency: **none**

### getHomework

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "status": {
      "type": "string"
    },
    "forceRefresh": {
      "type": "boolean"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/homeworkRead"
}
```

Errors: `permission_denied`, `request_timeout`, `network_timeout`.

## academic.resources@1

读取课程资源：读取课程资料目录与资源元数据。

Stability: **stable** · confirmation: **none** · idempotency: **none**

### getCourseResources

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "courseId"
  ],
  "properties": {
    "term": {
      "type": "string"
    },
    "courseId": {
      "type": "string"
    },
    "folderId": {
      "type": "string"
    },
    "search": {
      "type": "string"
    },
    "categoryKey": {
      "type": "string"
    },
    "forceRefresh": {
      "type": "boolean"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/courseResourcesRead"
}
```

Errors: `permission_denied`, `request_timeout`, `network_timeout`.

## mail.read@1

读取校园邮件：读取邮件文件夹、列表与正文。

Stability: **stable** · confirmation: **none** · idempotency: **none**

### listFolders

Request schema:

```json
{
  "$ref": "#/schemas/readOptions"
}
```

Response schema:

```json
{
  "$ref": "#/schemas/mailFoldersRead"
}
```

Errors: `permission_denied`, `request_timeout`, `network_timeout`.

### listMessages

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "folderId": {
      "type": "string"
    },
    "start": {
      "type": "integer",
      "minimum": 0
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "maximum": 100
    },
    "forceRefresh": {
      "type": "boolean"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/mailMessagesRead"
}
```

Errors: `permission_denied`, `request_timeout`, `network_timeout`.

### getMessage

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "messageId"
  ],
  "properties": {
    "messageId": {
      "type": "string"
    },
    "mailbox": {
      "type": "string"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/mailMessageRead"
}
```

Errors: `permission_denied`, `request_timeout`, `network_timeout`.

## campus.request@1

访问只读校园代理：调用宿主登记的 MIS、AA 或 VE 只读路径，不暴露会话信息。

Stability: **stable** · confirmation: **none** · idempotency: **none**

### request

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "service",
    "path"
  ],
  "properties": {
    "service": {
      "type": "string",
      "enum": [
        "mis",
        "aa",
        "ve"
      ]
    },
    "method": {
      "type": "string",
      "enum": [
        "GET",
        "HEAD"
      ]
    },
    "path": {
      "type": "string"
    },
    "query": {
      "type": "object"
    },
    "accept": {
      "type": "string"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/campusRead"
}
```

Errors: `permission_denied`, `request_timeout`, `invalid_request`, `http_error`, `resource_too_large`.

## network.request@1

通过宿主访问公网：使用不含 Cookie 和宿主认证信息的隔离网络客户端访问已声明来源。

Stability: **beta** · confirmation: **none** · idempotency: **none**

Events: `progress`.

### request

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "url"
  ],
  "properties": {
    "url": {
      "type": "string"
    },
    "method": {
      "type": "string",
      "enum": [
        "GET",
        "HEAD",
        "POST",
        "PUT",
        "PATCH",
        "DELETE"
      ]
    },
    "headers": {
      "type": "object"
    },
    "body": {},
    "bodyType": {
      "type": "string",
      "enum": [
        "json",
        "text",
        "formData",
        "blob"
      ]
    },
    "timeoutMs": {
      "type": "integer",
      "minimum": 1,
      "maximum": 60000
    }
  }
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "status",
    "headers",
    "bodyType",
    "finalUrl",
    "redirects"
  ],
  "properties": {
    "status": {
      "type": "integer"
    },
    "headers": {
      "type": "object"
    },
    "bodyType": {
      "type": "string",
      "enum": [
        "json",
        "text",
        "resource"
      ]
    },
    "body": {},
    "resource": {
      "$ref": "#/schemas/resourceHandle"
    },
    "finalUrl": {
      "type": "string"
    },
    "redirects": {
      "type": "integer"
    },
    "contentType": {
      "type": "string"
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `origin_denied`, `network_timeout`, `http_error`, `quota_exceeded`, `resource_too_large`, `user_cancelled`.

## storage.kv@2

保存插件数据：在当前发布者与插件隔离的加密空间中保存 JSON 数据。

Stability: **beta** · confirmation: **none** · idempotency: **none**

Events: `changed`.

### get

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "key"
  ],
  "properties": {
    "key": {
      "type": "string"
    }
  }
}
```

Response schema:

```json
{
  "type": "object",
  "required": [
    "value",
    "revision"
  ],
  "properties": {
    "value": {},
    "revision": {
      "type": "integer"
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `invalid_request`.

### set

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "key",
    "value"
  ],
  "properties": {
    "key": {
      "type": "string"
    },
    "value": {},
    "ifRevision": {
      "type": "integer"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/kvTransaction"
}
```

Errors: `permission_denied`, `request_timeout`, `invalid_request`, `quota_exceeded`, `resource_too_large`, `idempotency_conflict`.

### remove

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "key"
  ],
  "properties": {
    "key": {
      "type": "string"
    },
    "ifRevision": {
      "type": "integer"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/kvRemove"
}
```

Errors: `permission_denied`, `request_timeout`, `invalid_request`, `idempotency_conflict`.

### keys

Request schema:

```json
{
  "$ref": "#/schemas/empty"
}
```

Response schema:

```json
{
  "type": "object",
  "required": [
    "keys",
    "revision"
  ],
  "properties": {
    "keys": {
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "revision": {
      "type": "integer"
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`.

### usage

Request schema:

```json
{
  "$ref": "#/schemas/empty"
}
```

Response schema:

```json
{
  "$ref": "#/schemas/kvUsage"
}
```

Errors: `permission_denied`, `request_timeout`.

### batch

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "operations"
  ],
  "properties": {
    "operations": {
      "type": "array",
      "maxItems": 256,
      "items": {
        "type": "object"
      }
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/kvTransaction"
}
```

Errors: `permission_denied`, `request_timeout`, `invalid_request`, `quota_exceeded`, `resource_too_large`, `idempotency_conflict`.

### transaction

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "ifRevision",
    "operations"
  ],
  "properties": {
    "ifRevision": {
      "type": "integer"
    },
    "operations": {
      "type": "array",
      "maxItems": 256,
      "items": {
        "type": "object"
      }
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/kvTransaction"
}
```

Errors: `permission_denied`, `request_timeout`, `invalid_request`, `quota_exceeded`, `resource_too_large`, `idempotency_conflict`.

### export

Request schema:

```json
{
  "$ref": "#/schemas/empty"
}
```

Response schema:

```json
{
  "$ref": "#/schemas/resourceHandle"
}
```

Errors: `permission_denied`, `request_timeout`, `resource_too_large`.

### import

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "handle"
  ],
  "properties": {
    "handle": {
      "type": "string"
    },
    "ifRevision": {
      "type": "integer"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/kvTransaction"
}
```

Errors: `permission_denied`, `request_timeout`, `invalid_request`, `quota_exceeded`, `resource_too_large`, `migration_failed`, `idempotency_conflict`.

## storage.blob@1

保存大文件：在隔离的加密 Blob 空间保存不可变内容寻址数据。

Stability: **beta** · confirmation: **none** · idempotency: **none**

### put

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "contentType",
    "size"
  ],
  "properties": {
    "contentType": {
      "type": "string"
    },
    "size": {
      "type": "integer",
      "minimum": 0,
      "maximum": 67108864
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/resourceHandle"
}
```

Errors: `permission_denied`, `request_timeout`, `invalid_request`, `quota_exceeded`, `resource_too_large`, `user_cancelled`.

### getInfo

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "handle"
  ],
  "properties": {
    "handle": {
      "type": "string"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/resourceHandle"
}
```

Errors: `permission_denied`, `request_timeout`, `invalid_request`.

### delete

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "handle"
  ],
  "properties": {
    "handle": {
      "type": "string"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/deletionResult"
}
```

Errors: `permission_denied`, `request_timeout`, `invalid_request`.

## cache.resource@1

缓存网络资源：在可淘汰的隔离 LRU 缓存中保存资源。

Stability: **beta** · confirmation: **none** · idempotency: **none**

### put

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "key",
    "contentType",
    "size"
  ],
  "properties": {
    "key": {
      "type": "string"
    },
    "contentType": {
      "type": "string"
    },
    "size": {
      "type": "integer",
      "minimum": 0,
      "maximum": 262144000
    },
    "pin": {
      "type": "boolean"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/resourceHandle"
}
```

Errors: `permission_denied`, `request_timeout`, `invalid_request`, `quota_exceeded`, `resource_too_large`, `user_cancelled`.

### promote

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "handle",
    "key"
  ],
  "properties": {
    "handle": {
      "type": "string"
    },
    "key": {
      "type": "string"
    },
    "pinned": {
      "type": "boolean"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/resourceHandle"
}
```

Errors: `permission_denied`, `request_timeout`, `invalid_request`, `quota_exceeded`.

### deleteHandle

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "handle"
  ],
  "properties": {
    "handle": {
      "type": "string"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/deletionResult"
}
```

Errors: `permission_denied`, `request_timeout`, `invalid_request`.

### match

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "key"
  ],
  "properties": {
    "key": {
      "type": "string"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/resourceHandleOrNull"
}
```

Errors: `permission_denied`, `request_timeout`, `invalid_request`.

### delete

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "key"
  ],
  "properties": {
    "key": {
      "type": "string"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/deletionResult"
}
```

Errors: `permission_denied`, `request_timeout`, `invalid_request`.

### pin

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "key",
    "pinned"
  ],
  "properties": {
    "key": {
      "type": "string"
    },
    "pinned": {
      "type": "boolean"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/pinResult"
}
```

Errors: `permission_denied`, `request_timeout`, `invalid_request`.

### usage

Request schema:

```json
{
  "$ref": "#/schemas/empty"
}
```

Response schema:

```json
{
  "$ref": "#/schemas/cacheUsage"
}
```

Errors: `permission_denied`, `request_timeout`.

## android.accessibility.events@1

读取无障碍事件：读取用户启用的 Android 无障碍事件；持久订阅会在插件后台运行时继续生效。

Stability: **beta** · confirmation: **none** · idempotency: **none**

Events: `received`.

### getStatus

Request schema:

```json
{
  "$ref": "#/schemas/empty"
}
```

Response schema:

```json
{
  "$ref": "#/schemas/accessibilityStatus"
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`.

### subscribe

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "eventTypes",
    "persistent",
    "includeSource"
  ],
  "properties": {
    "eventTypes": {
      "type": "array",
      "minItems": 1,
      "maxItems": 32,
      "items": {
        "$ref": "#/schemas/accessibilityEventType"
      }
    },
    "packageNames": {
      "type": "array",
      "maxItems": 64,
      "items": {
        "type": "string",
        "pattern": "^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$"
      }
    },
    "persistent": {
      "type": "boolean"
    },
    "includeSource": {
      "type": "boolean"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/accessibilitySubscription"
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`, `quota_exceeded`.

### unsubscribe

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "subscriptionId"
  ],
  "properties": {
    "subscriptionId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 128
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/deletionResult"
}
```

Errors: `permission_denied`, `request_timeout`, `invalid_request`.

### listSubscriptions

Request schema:

```json
{
  "$ref": "#/schemas/empty"
}
```

Response schema:

```json
{
  "type": "array",
  "items": {
    "$ref": "#/schemas/accessibilitySubscription"
  }
}
```

Errors: `permission_denied`, `request_timeout`.

## android.accessibility.nodes@1

读取无障碍节点：读取活动窗口的结构化节点快照；密码和敏感输入值始终脱敏。

Stability: **beta** · confirmation: **none** · idempotency: **none**

### getRoot

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "windowId": {
      "type": "integer"
    },
    "maxDepth": {
      "type": "integer",
      "minimum": 1,
      "maximum": 64
    },
    "maxNodes": {
      "type": "integer",
      "minimum": 1,
      "maximum": 4096
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/accessibilityNode"
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`, `resource_too_large`.

### find

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "selector"
  ],
  "properties": {
    "windowId": {
      "type": "integer"
    },
    "selector": {
      "$ref": "#/schemas/accessibilityNodeSelector"
    },
    "maxResults": {
      "type": "integer",
      "minimum": 1,
      "maximum": 256
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/accessibilityNodeList"
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`, `resource_too_large`.

### get

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "nodeId"
  ],
  "properties": {
    "nodeId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/accessibilityNode"
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`, `invalid_request`, `quota_exceeded`.

## android.accessibility.actions@1

执行无障碍动作：允许插件操作其他应用的无障碍节点、全局导航和触摸手势；首次授权后持续有效。

Stability: **beta** · confirmation: **none** · idempotency: **required**

### performNode

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "idempotencyKey",
    "nodeId",
    "action"
  ],
  "properties": {
    "idempotencyKey": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "nodeId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "action": {
      "type": "string",
      "enum": [
        "click",
        "longClick",
        "focus",
        "clearFocus",
        "select",
        "clearSelection",
        "scrollForward",
        "scrollBackward",
        "scrollUp",
        "scrollDown",
        "scrollLeft",
        "scrollRight",
        "expand",
        "collapse",
        "dismiss",
        "showOnScreen",
        "setText",
        "setSelection",
        "copy",
        "paste"
      ]
    },
    "arguments": {
      "type": "object",
      "additionalProperties": true
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/automationReceipt"
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`, `invalid_request`, `quota_exceeded`, `idempotency_conflict`.

### performGlobal

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "idempotencyKey",
    "action"
  ],
  "properties": {
    "idempotencyKey": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "action": {
      "type": "string",
      "enum": [
        "back",
        "home",
        "recents",
        "notifications",
        "quickSettings",
        "powerDialog",
        "splitScreen"
      ]
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/automationReceipt"
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`, `invalid_request`, `quota_exceeded`, `idempotency_conflict`.

### dispatchGesture

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "idempotencyKey",
    "strokes"
  ],
  "properties": {
    "idempotencyKey": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "strokes": {
      "type": "array",
      "minItems": 1,
      "maxItems": 16,
      "items": {
        "$ref": "#/schemas/accessibilityGestureStroke"
      }
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/automationReceipt"
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`, `invalid_request`, `quota_exceeded`, `idempotency_conflict`.

## android.packages.read@1

读取已安装应用：读取设备上的应用包、权限、组件和签名摘要；不读取应用私有数据。

Stability: **beta** · confirmation: **none** · idempotency: **none**

### list

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "includeSystem": {
      "type": "boolean"
    },
    "includeDisabled": {
      "type": "boolean"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/packageList"
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`, `resource_too_large`.

### get

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "packageName"
  ],
  "properties": {
    "packageName": {
      "type": "string",
      "pattern": "^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/packageInfo"
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`, `invalid_request`.

### resolveIntent

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "action"
  ],
  "properties": {
    "action": {
      "type": "string",
      "minLength": 1,
      "maxLength": 256
    },
    "dataUri": {
      "type": "string",
      "maxLength": 512
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/resolvedActivityList"
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`, `invalid_request`.

## android.settings.open@1

打开系统设置：打开 android.settings.* 系统设置页面；宿主拒绝任意 Intent、组件和额外参数。

Stability: **beta** · confirmation: **none** · idempotency: **none**

### open

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "action"
  ],
  "properties": {
    "action": {
      "type": "string",
      "pattern": "^android\\.settings\\.[A-Z0-9_]+$"
    },
    "packageName": {
      "type": "string",
      "pattern": "^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/settingsOpenResult"
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`, `invalid_request`, `quota_exceeded`.

## android.device.info@1

undefined：读取最小化的设备和宿主应用信息；不包含稳定硬件标识符。

Stability: **beta** · confirmation: **none** · idempotency: **none**

### getInfo

Request schema:

```json
{
  "$ref": "#/schemas/empty"
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "platform",
    "sdkInt",
    "manufacturer",
    "model",
    "locale",
    "timezone",
    "appVersion"
  ],
  "properties": {
    "platform": {
      "const": "android"
    },
    "sdkInt": {
      "type": "integer"
    },
    "manufacturer": {
      "type": "string",
      "maxLength": 120
    },
    "model": {
      "type": "string",
      "maxLength": 160
    },
    "locale": {
      "type": "string",
      "maxLength": 64
    },
    "timezone": {
      "type": "string",
      "maxLength": 128
    },
    "appVersion": {
      "type": "string",
      "maxLength": 120
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`.

## android.network.status@1

undefined：读取和订阅最小化网络状态；不返回 SSID、BSSID、MAC 或 IP。

Stability: **beta** · confirmation: **none** · idempotency: **none**

Events: `changed`.

### getStatus

Request schema:

```json
{
  "$ref": "#/schemas/empty"
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "online",
    "validated",
    "metered",
    "transport"
  ],
  "properties": {
    "online": {
      "type": "boolean"
    },
    "validated": {
      "type": "boolean"
    },
    "metered": {
      "type": "boolean"
    },
    "transport": {
      "type": "string",
      "enum": [
        "wifi",
        "cellular",
        "ethernet",
        "vpn",
        "other",
        "none"
      ]
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`.

### subscribe

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "persistent": {
      "type": "boolean",
      "default": false
    }
  }
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "subscriptionId",
    "persistent"
  ],
  "properties": {
    "subscriptionId": {
      "type": "string"
    },
    "persistent": {
      "type": "boolean"
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `quota_exceeded`, `capability_unavailable`.

### unsubscribe

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "subscriptionId"
  ],
  "properties": {
    "subscriptionId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    }
  }
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "deleted"
  ],
  "properties": {
    "deleted": {
      "type": "boolean"
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`.

## android.battery.status@1

undefined：读取和订阅电池状态。

Stability: **beta** · confirmation: **none** · idempotency: **none**

Events: `changed`.

### getStatus

Request schema:

```json
{
  "$ref": "#/schemas/empty"
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "level",
    "charging",
    "status"
  ],
  "properties": {
    "level": {
      "type": "integer",
      "minimum": 0,
      "maximum": 100
    },
    "charging": {
      "type": "boolean"
    },
    "status": {
      "type": "string",
      "enum": [
        "charging",
        "discharging",
        "full",
        "notCharging",
        "unknown"
      ]
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`.

### subscribe

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "persistent": {
      "type": "boolean",
      "default": false
    }
  }
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "subscriptionId",
    "persistent"
  ],
  "properties": {
    "subscriptionId": {
      "type": "string"
    },
    "persistent": {
      "type": "boolean"
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `quota_exceeded`, `capability_unavailable`.

### unsubscribe

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "subscriptionId"
  ],
  "properties": {
    "subscriptionId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    }
  }
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "deleted"
  ],
  "properties": {
    "deleted": {
      "type": "boolean"
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`.

## android.haptics.perform@1

undefined：执行受限时长的设备振动反馈。

Stability: **beta** · confirmation: **none** · idempotency: **none**

### perform

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "durationMs"
  ],
  "properties": {
    "durationMs": {
      "type": "integer",
      "minimum": 1,
      "maximum": 1000
    }
  }
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "performed",
    "durationMs"
  ],
  "properties": {
    "performed": {
      "type": "boolean"
    },
    "durationMs": {
      "type": "integer"
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`, `quota_exceeded`.

## android.files.pick@1

undefined：通过系统文件选择器导入受限大小的文件到插件加密 blob 存储。

Stability: **beta** · confirmation: **none** · idempotency: **none**

### pick

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "mimeTypes": {
      "type": "array",
      "maxItems": 16,
      "uniqueItems": true,
      "items": {
        "type": "string",
        "minLength": 3,
        "maxLength": 120
      }
    },
    "multiple": {
      "type": "boolean",
      "default": false
    }
  }
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "items"
  ],
  "properties": {
    "items": {
      "type": "array",
      "maxItems": 16,
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": [
          "handle",
          "name",
          "mimeType",
          "size"
        ],
        "properties": {
          "handle": {
            "type": "string"
          },
          "name": {
            "type": "string",
            "maxLength": 255
          },
          "mimeType": {
            "type": "string",
            "maxLength": 160
          },
          "size": {
            "type": "integer",
            "minimum": 0
          }
        }
      }
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `foreground_required`, `user_cancelled`, `quota_exceeded`, `resource_too_large`, `capability_unavailable`.

## android.files.save@1

undefined：通过系统保存面板将当前插件 blob 导出为用户选择的文件。

Stability: **beta** · confirmation: **none** · idempotency: **required**

### save

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "idempotencyKey",
    "handle",
    "fileName",
    "mimeType"
  ],
  "properties": {
    "idempotencyKey": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "handle": {
      "type": "string",
      "pattern": "^blob-[a-f0-9]{64}$"
    },
    "fileName": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "mimeType": {
      "type": "string",
      "minLength": 3,
      "maxLength": 160
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/commandReceipt"
}
```

Errors: `permission_denied`, `request_timeout`, `foreground_required`, `user_cancelled`, `invalid_request`, `resource_too_large`, `idempotency_conflict`, `capability_unavailable`.

## android.media.pick@1

undefined：通过系统照片选择器导入图片或视频到插件加密 blob 存储。

Stability: **beta** · confirmation: **none** · idempotency: **none**

### pick

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "mediaType": {
      "type": "string",
      "enum": [
        "image",
        "video",
        "mixed"
      ],
      "default": "image"
    },
    "multiple": {
      "type": "boolean",
      "default": false
    }
  }
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "items"
  ],
  "properties": {
    "items": {
      "type": "array",
      "maxItems": 16,
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": [
          "handle",
          "name",
          "mimeType",
          "size"
        ],
        "properties": {
          "handle": {
            "type": "string"
          },
          "name": {
            "type": "string",
            "maxLength": 255
          },
          "mimeType": {
            "type": "string",
            "maxLength": 160
          },
          "size": {
            "type": "integer",
            "minimum": 0
          }
        }
      }
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `foreground_required`, `user_cancelled`, `quota_exceeded`, `resource_too_large`, `capability_unavailable`.

## android.share.open@1

undefined：通过 Android chooser 分享文本、HTTPS URL 或当前插件 blob。

Stability: **beta** · confirmation: **none** · idempotency: **none**

### open

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "title": {
      "type": "string",
      "maxLength": 120
    },
    "text": {
      "type": "string",
      "maxLength": 8192
    },
    "url": {
      "type": "string",
      "pattern": "^https://",
      "maxLength": 2048
    },
    "handle": {
      "type": "string",
      "pattern": "^blob-[a-f0-9]{64}$"
    }
  }
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "opened"
  ],
  "properties": {
    "opened": {
      "type": "boolean"
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `foreground_required`, `invalid_request`, `quota_exceeded`, `capability_unavailable`.

## android.notifications.post@1

undefined：显示、计划和取消受配额约束的插件本地通知。

Stability: **beta** · confirmation: **none** · idempotency: **required**

### getStatus

Request schema:

```json
{
  "$ref": "#/schemas/empty"
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "granted",
    "enabled"
  ],
  "properties": {
    "granted": {
      "type": "boolean"
    },
    "enabled": {
      "type": "boolean"
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`.

### show

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "idempotencyKey",
    "id",
    "title",
    "body"
  ],
  "properties": {
    "idempotencyKey": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "id": {
      "type": "string",
      "minLength": 1,
      "maxLength": 120
    },
    "title": {
      "type": "string",
      "minLength": 1,
      "maxLength": 120
    },
    "body": {
      "type": "string",
      "maxLength": 1024
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/commandReceipt"
}
```

Errors: `permission_denied`, `request_timeout`, `foreground_required`, `quota_exceeded`, `idempotency_conflict`, `capability_unavailable`.

### schedule

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "idempotencyKey",
    "id",
    "title",
    "body",
    "triggerAtMs"
  ],
  "properties": {
    "idempotencyKey": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "id": {
      "type": "string",
      "minLength": 1,
      "maxLength": 120
    },
    "title": {
      "type": "string",
      "minLength": 1,
      "maxLength": 120
    },
    "body": {
      "type": "string",
      "maxLength": 1024
    },
    "triggerAtMs": {
      "type": "integer",
      "minimum": 0
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/commandReceipt"
}
```

Errors: `permission_denied`, `request_timeout`, `foreground_required`, `quota_exceeded`, `idempotency_conflict`, `capability_unavailable`.

### cancel

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "idempotencyKey",
    "id"
  ],
  "properties": {
    "idempotencyKey": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "id": {
      "type": "string",
      "minLength": 1,
      "maxLength": 120
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/commandReceipt"
}
```

Errors: `permission_denied`, `request_timeout`, `idempotency_conflict`, `capability_unavailable`.

## android.location.read@1

undefined：读取一次前台位置；不会请求后台定位。

Stability: **beta** · confirmation: **none** · idempotency: **none**

### getStatus

Request schema:

```json
{
  "$ref": "#/schemas/empty"
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "granted",
    "enabled"
  ],
  "properties": {
    "granted": {
      "type": "boolean"
    },
    "enabled": {
      "type": "boolean"
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`.

### getCurrent

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "highAccuracy": {
      "type": "boolean",
      "default": false
    },
    "timeoutMs": {
      "type": "integer",
      "minimum": 1000,
      "maximum": 60000,
      "default": 15000
    }
  }
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "latitude",
    "longitude",
    "time"
  ],
  "properties": {
    "latitude": {
      "type": "number",
      "minimum": -90,
      "maximum": 90
    },
    "longitude": {
      "type": "number",
      "minimum": -180,
      "maximum": 180
    },
    "accuracy": {
      "type": "number",
      "minimum": 0
    },
    "time": {
      "type": "integer",
      "minimum": 0
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `foreground_required`, `capability_unavailable`, `quota_exceeded`.

## android.calendar.read@1

undefined：读取受日期和条数限制的系统日历事件。

Stability: **beta** · confirmation: **none** · idempotency: **none**

### list

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "startMs",
    "endMs"
  ],
  "properties": {
    "startMs": {
      "type": "integer",
      "minimum": 0
    },
    "endMs": {
      "type": "integer",
      "minimum": 0
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "maximum": 200,
      "default": 50
    }
  }
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "events"
  ],
  "properties": {
    "events": {
      "type": "array",
      "maxItems": 200,
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": [
          "id",
          "title",
          "startMs",
          "endMs",
          "allDay"
        ],
        "properties": {
          "id": {
            "type": "string"
          },
          "title": {
            "type": "string",
            "maxLength": 1024
          },
          "description": {
            "type": "string",
            "maxLength": 4096
          },
          "location": {
            "type": "string",
            "maxLength": 1024
          },
          "startMs": {
            "type": "integer"
          },
          "endMs": {
            "type": "integer"
          },
          "allDay": {
            "type": "boolean"
          }
        }
      }
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `foreground_required`, `invalid_request`, `quota_exceeded`, `capability_unavailable`.

## android.calendar.write@1

undefined：创建、更新或删除日历事件；使用幂等回执。

Stability: **beta** · confirmation: **none** · idempotency: **required**

### create

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "idempotencyKey",
    "title",
    "startMs",
    "endMs"
  ],
  "properties": {
    "idempotencyKey": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "title": {
      "type": "string",
      "minLength": 1,
      "maxLength": 1024
    },
    "description": {
      "type": "string",
      "maxLength": 4096
    },
    "location": {
      "type": "string",
      "maxLength": 1024
    },
    "startMs": {
      "type": "integer",
      "minimum": 0
    },
    "endMs": {
      "type": "integer",
      "minimum": 0
    },
    "allDay": {
      "type": "boolean",
      "default": false
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/commandReceipt"
}
```

Errors: `permission_denied`, `request_timeout`, `foreground_required`, `invalid_request`, `idempotency_conflict`, `capability_unavailable`.

### update

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "idempotencyKey",
    "id"
  ],
  "properties": {
    "idempotencyKey": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "id": {
      "type": "string",
      "minLength": 1,
      "maxLength": 64
    },
    "title": {
      "type": "string",
      "maxLength": 1024
    },
    "description": {
      "type": "string",
      "maxLength": 4096
    },
    "location": {
      "type": "string",
      "maxLength": 1024
    },
    "startMs": {
      "type": "integer",
      "minimum": 0
    },
    "endMs": {
      "type": "integer",
      "minimum": 0
    },
    "allDay": {
      "type": "boolean"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/commandReceipt"
}
```

Errors: `permission_denied`, `request_timeout`, `foreground_required`, `invalid_request`, `idempotency_conflict`, `capability_unavailable`.

### delete

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "idempotencyKey",
    "id"
  ],
  "properties": {
    "idempotencyKey": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "id": {
      "type": "string",
      "minLength": 1,
      "maxLength": 64
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/commandReceipt"
}
```

Errors: `permission_denied`, `request_timeout`, `foreground_required`, `invalid_request`, `idempotency_conflict`, `capability_unavailable`.

## android.camera.capture@1

undefined：通过系统相机 UI 拍摄照片并导入加密 blob 存储。

Stability: **beta** · confirmation: **none** · idempotency: **none**

### capturePhoto

Request schema:

```json
{
  "$ref": "#/schemas/empty"
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "handle",
    "mimeType",
    "size"
  ],
  "properties": {
    "handle": {
      "type": "string"
    },
    "mimeType": {
      "const": "image/jpeg"
    },
    "size": {
      "type": "integer",
      "minimum": 0
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `foreground_required`, `user_cancelled`, `resource_too_large`, `capability_unavailable`.

## android.audio.record@1

undefined：在可见前台插件 runtime 中录制受时长限制的音频。

Stability: **beta** · confirmation: **none** · idempotency: **required**

### start

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "idempotencyKey",
    "recordingId"
  ],
  "properties": {
    "idempotencyKey": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "recordingId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 120
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/commandReceipt"
}
```

Errors: `permission_denied`, `request_timeout`, `foreground_required`, `invalid_request`, `idempotency_conflict`, `capability_unavailable`.

### stop

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "idempotencyKey",
    "recordingId"
  ],
  "properties": {
    "idempotencyKey": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "recordingId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 120
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/commandReceipt"
}
```

Errors: `permission_denied`, `request_timeout`, `foreground_required`, `invalid_request`, `resource_too_large`, `idempotency_conflict`, `capability_unavailable`.

## android.sensors.read@1

undefined：读取受限频率的常规设备传感器，不包含 body sensor 数据。

Stability: **beta** · confirmation: **none** · idempotency: **none**

Events: `changed`.

### list

Request schema:

```json
{
  "$ref": "#/schemas/empty"
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "sensors"
  ],
  "properties": {
    "sensors": {
      "type": "array",
      "items": {
        "type": "string",
        "enum": [
          "accelerometer",
          "gyroscope",
          "magneticField",
          "light",
          "pressure"
        ]
      }
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`.

### subscribe

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "sensor"
  ],
  "properties": {
    "sensor": {
      "type": "string",
      "enum": [
        "accelerometer",
        "gyroscope",
        "magneticField",
        "light",
        "pressure"
      ]
    },
    "persistent": {
      "type": "boolean",
      "default": false
    },
    "rateHz": {
      "type": "integer",
      "minimum": 1,
      "maximum": 20,
      "default": 5
    }
  }
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "subscriptionId",
    "persistent",
    "rateHz"
  ],
  "properties": {
    "subscriptionId": {
      "type": "string"
    },
    "persistent": {
      "type": "boolean"
    },
    "rateHz": {
      "type": "integer",
      "minimum": 1,
      "maximum": 20
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `quota_exceeded`, `capability_unavailable`.

### unsubscribe

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "subscriptionId"
  ],
  "properties": {
    "subscriptionId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    }
  }
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "deleted"
  ],
  "properties": {
    "deleted": {
      "type": "boolean"
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`.

## android.biometric.verify@1

undefined：打开系统生物识别确认，只返回结果且不读取生物特征数据。

Stability: **beta** · confirmation: **none** · idempotency: **none**

### getStatus

Request schema:

```json
{
  "$ref": "#/schemas/empty"
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "available"
  ],
  "properties": {
    "available": {
      "type": "boolean"
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`.

### verify

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "title"
  ],
  "properties": {
    "title": {
      "type": "string",
      "minLength": 1,
      "maxLength": 120
    },
    "subtitle": {
      "type": "string",
      "maxLength": 240
    }
  }
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "verified"
  ],
  "properties": {
    "verified": {
      "type": "boolean"
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `foreground_required`, `user_cancelled`, `capability_unavailable`, `quota_exceeded`.

## academic.userCourses.command@1

修改自定义课程：新增、修改或删除用户自定义课程。

Stability: **beta** · confirmation: **eachCall** · idempotency: **required**

### save

Request schema:

```json
{
  "type": "object",
  "required": [
    "idempotencyKey",
    "course"
  ],
  "properties": {
    "idempotencyKey": {
      "type": "string"
    },
    "course": {
      "type": "object"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/commandReceipt"
}
```

Errors: `permission_denied`, `request_timeout`, `user_cancelled`, `idempotency_conflict`.

### delete

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "idempotencyKey",
    "id"
  ],
  "properties": {
    "idempotencyKey": {
      "type": "string"
    },
    "id": {
      "type": "integer"
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/commandReceipt"
}
```

Errors: `permission_denied`, `request_timeout`, `user_cancelled`, `idempotency_conflict`.

## academic.homework.submit@1

提交作业：向课程平台提交作业；每次调用都需要用户确认。

Stability: **beta** · confirmation: **eachCall** · idempotency: **required**

### submit

Request schema:

```json
{
  "type": "object",
  "required": [
    "idempotencyKey",
    "homeworkId",
    "courseId"
  ],
  "properties": {
    "idempotencyKey": {
      "type": "string"
    },
    "homeworkId": {
      "type": "integer"
    },
    "courseId": {
      "type": "integer"
    },
    "content": {
      "type": "string"
    },
    "attachmentHandles": {
      "type": "array",
      "items": {
        "type": "string"
      }
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/commandReceipt"
}
```

Errors: `permission_denied`, `request_timeout`, `user_cancelled`, `idempotency_conflict`, `http_error`.

## mail.send@1

发送校园邮件：发送校园邮件；每次调用都需要用户确认。

Stability: **beta** · confirmation: **eachCall** · idempotency: **required**

### send

Request schema:

```json
{
  "type": "object",
  "required": [
    "idempotencyKey",
    "to",
    "subject"
  ],
  "properties": {
    "idempotencyKey": {
      "type": "string"
    },
    "to": {
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "cc": {
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "bcc": {
      "type": "array",
      "items": {
        "type": "string"
      }
    },
    "subject": {
      "type": "string"
    },
    "text": {
      "type": "string"
    },
    "html": {
      "type": "string"
    },
    "attachmentHandles": {
      "type": "array",
      "items": {
        "type": "string"
      }
    }
  }
}
```

Response schema:

```json
{
  "$ref": "#/schemas/commandReceipt"
}
```

Errors: `permission_denied`, `request_timeout`, `user_cancelled`, `idempotency_conflict`, `http_error`.

## android.app.autoStart@1

打开主应用时自动启动插件：允许插件在你打开 BJTU MIS 主应用且主 Activity 可见时加载并在后台运行。默认关闭，不会开机启动或绕过系统权限。

Stability: **beta** · confirmation: **none** · idempotency: **none**

## android.session.keepAlive@1

维持 MIS 会话：允许插件在限时任务期间维持 BJTU MIS 会话，并显示持续通知。

Stability: **beta** · confirmation: **none** · idempotency: **required**

Events: `ended`.

### acquire

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "idempotencyKey",
    "requestedDurationMs"
  ],
  "properties": {
    "idempotencyKey": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "requestedDurationMs": {
      "type": "integer",
      "minimum": 60000,
      "maximum": 3600000
    }
  }
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "receiptId",
    "idempotencyKey",
    "completedAt",
    "result"
  ],
  "properties": {
    "receiptId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "idempotencyKey": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "completedAt": {
      "type": "string"
    },
    "result": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "released"
      ],
      "properties": {
        "lease": {
          "type": "object",
          "additionalProperties": false,
          "required": [
            "leaseId",
            "expiresAtMs",
            "maxExpiresAtMs"
          ],
          "properties": {
            "leaseId": {
              "type": "string",
              "minLength": 1,
              "maxLength": 160
            },
            "expiresAtMs": {
              "type": "integer",
              "minimum": 0
            },
            "maxExpiresAtMs": {
              "type": "integer",
              "minimum": 0
            }
          }
        },
        "released": {
          "type": "boolean"
        }
      }
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`, `foreground_required`, `invalid_request`, `quota_exceeded`, `idempotency_conflict`, `lease_not_found`, `foreground_service_denied`, `notifications_unavailable`.

### renew

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "idempotencyKey",
    "leaseId",
    "requestedDurationMs"
  ],
  "properties": {
    "idempotencyKey": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "leaseId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "requestedDurationMs": {
      "type": "integer",
      "minimum": 60000,
      "maximum": 3600000
    }
  }
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "receiptId",
    "idempotencyKey",
    "completedAt",
    "result"
  ],
  "properties": {
    "receiptId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "idempotencyKey": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "completedAt": {
      "type": "string"
    },
    "result": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "released"
      ],
      "properties": {
        "lease": {
          "type": "object",
          "additionalProperties": false,
          "required": [
            "leaseId",
            "expiresAtMs",
            "maxExpiresAtMs"
          ],
          "properties": {
            "leaseId": {
              "type": "string",
              "minLength": 1,
              "maxLength": 160
            },
            "expiresAtMs": {
              "type": "integer",
              "minimum": 0
            },
            "maxExpiresAtMs": {
              "type": "integer",
              "minimum": 0
            }
          }
        },
        "released": {
          "type": "boolean"
        }
      }
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`, `foreground_required`, `invalid_request`, `quota_exceeded`, `idempotency_conflict`, `lease_not_found`, `foreground_service_denied`, `notifications_unavailable`.

### release

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "idempotencyKey",
    "leaseId"
  ],
  "properties": {
    "idempotencyKey": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "leaseId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    }
  }
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "receiptId",
    "idempotencyKey",
    "completedAt",
    "result"
  ],
  "properties": {
    "receiptId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "idempotencyKey": {
      "type": "string",
      "minLength": 1,
      "maxLength": 160
    },
    "completedAt": {
      "type": "string"
    },
    "result": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "released"
      ],
      "properties": {
        "lease": {
          "type": "object",
          "additionalProperties": false,
          "required": [
            "leaseId",
            "expiresAtMs",
            "maxExpiresAtMs"
          ],
          "properties": {
            "leaseId": {
              "type": "string",
              "minLength": 1,
              "maxLength": 160
            },
            "expiresAtMs": {
              "type": "integer",
              "minimum": 0
            },
            "maxExpiresAtMs": {
              "type": "integer",
              "minimum": 0
            }
          }
        },
        "released": {
          "type": "boolean"
        }
      }
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`, `foreground_required`, `invalid_request`, `quota_exceeded`, `idempotency_conflict`, `lease_not_found`, `foreground_service_denied`, `notifications_unavailable`.

### getStatus

Request schema:

```json
{
  "type": "object",
  "additionalProperties": false
}
```

Response schema:

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": [
    "active",
    "serviceState",
    "leases"
  ],
  "properties": {
    "active": {
      "type": "boolean"
    },
    "serviceState": {
      "type": "string",
      "enum": [
        "stopped",
        "pending",
        "running",
        "degraded"
      ]
    },
    "leases": {
      "type": "array",
      "maxItems": 2,
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": [
          "leaseId",
          "expiresAtMs",
          "maxExpiresAtMs"
        ],
        "properties": {
          "leaseId": {
            "type": "string",
            "minLength": 1,
            "maxLength": 160
          },
          "expiresAtMs": {
            "type": "integer",
            "minimum": 0
          },
          "maxExpiresAtMs": {
            "type": "integer",
            "minimum": 0
          }
        }
      }
    }
  }
}
```

Errors: `permission_denied`, `request_timeout`, `capability_unavailable`, `foreground_required`, `invalid_request`, `quota_exceeded`, `idempotency_conflict`, `lease_not_found`, `foreground_service_denied`, `notifications_unavailable`.
