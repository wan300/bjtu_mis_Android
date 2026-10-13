/* This file is generated from plugin-tooling/contracts/capability-contracts.json. */
/* Do not edit by hand. Run `npm run generate` from plugin-tooling. */

export const CONTRACT_PROFILE = "contract_v1" as const;
export const MANIFEST_SCHEMA_VERSION = 3 as const;
export const PROTOCOL_VERSION = 2 as const;
export const RUNTIME_FLOOR = 2 as const;
export const PACKAGE_LIMITS = {
  "archiveBytes": 26214400,
  "extractedBytes": 52428800,
  "files": 1000,
  "iconBytes": 1048576
} as const;
export const PLUGIN_ERROR_CODES = [
  "permission_denied",
  "capability_unavailable",
  "invalid_request",
  "origin_denied",
  "network_timeout",
  "request_timeout",
  "http_error",
  "quota_exceeded",
  "resource_too_large",
  "migration_failed",
  "user_cancelled",
  "foreground_required",
  "idempotency_conflict",
  "lease_not_found",
  "foreground_service_denied",
  "notifications_unavailable"
] as const;
export type PluginErrorCode = (typeof PLUGIN_ERROR_CODES)[number];
export const CAPABILITY_IDS = [
  "runtime.lifecycle@1",
  "configuration.read@1",
  "remote.frame@1",
  "navigation.external@1",
  "identity.profile@1",
  "academic.timetable@1",
  "academic.scores@1",
  "academic.exams@1",
  "academic.calendar@1",
  "academic.progress@1",
  "academic.homework@1",
  "academic.resources@1",
  "mail.read@1",
  "campus.request@1",
  "network.request@1",
  "storage.kv@2",
  "storage.blob@1",
  "cache.resource@1",
  "android.accessibility.events@1",
  "android.accessibility.nodes@1",
  "android.accessibility.actions@1",
  "android.packages.read@1",
  "android.settings.open@1",
  "android.device.info@1",
  "android.network.status@1",
  "android.battery.status@1",
  "android.haptics.perform@1",
  "android.files.pick@1",
  "android.files.save@1",
  "android.media.pick@1",
  "android.share.open@1",
  "android.notifications.post@1",
  "android.location.read@1",
  "android.calendar.read@1",
  "android.calendar.write@1",
  "android.camera.capture@1",
  "android.audio.record@1",
  "android.sensors.read@1",
  "android.biometric.verify@1",
  "academic.userCourses.command@1",
  "academic.homework.submit@1",
  "mail.send@1",
  "android.app.autoStart@1",
  "android.session.keepAlive@1"
] as const;
export type CapabilityId = (typeof CAPABILITY_IDS)[number];

export interface CapabilityMethodMap {
  "runtime.lifecycle@1#handshake": { request: { "sdkVersion": string }; response: { "protocolVersion": number; "contractProfile": string; "runtimeFloor": number; "availableCapabilities": Array<string>; "binaryTransports": Array<"arraybuffer" | "base64url-chunks-v1">; "preferredBinaryTransport"?: "arraybuffer" | "base64url-chunks-v1" } };
  "runtime.lifecycle@1#ready": { request: Record<string, never>; response: { "ready": boolean } };
  "runtime.lifecycle@1#close": { request: Record<string, never>; response: { "closed": boolean } };
  "configuration.read@1#get": { request: { "key": string }; response: { "value": string | null } };
  "navigation.external@1#open": { request: { "url": string }; response: { "opened": boolean } };
  "identity.profile@1#getProfile": { request: { "forceRefresh"?: boolean }; response: { "data": { "name"?: string; "studentId"?: string; "account"?: string; "gender"?: string; "birthday"?: string; "college"?: string; "major"?: string; "className"?: string; "grade"?: string; "educationLevel"?: string; "studentStatus"?: string; "campus"?: string; "phone"?: string; "email"?: string; "avatarUrl"?: string; "fields": Array<Record<string, unknown>>; "sections": Array<Record<string, unknown>> }; "meta": { "syncedAt": string; "source": "cache" | "network" | "mixed"; "coverage": "complete" | "partial" | "unknown"; "fromCache": boolean } } };
  "academic.timetable@1#getTimetable": { request: { "forceRefresh"?: boolean }; response: { "data": { "days": Array<string>; "periods": Array<string>; "entries": Array<Record<string, unknown>>; "currentTerm"?: string; "availableTerms": Array<Record<string, unknown>> }; "meta": { "syncedAt": string; "source": "cache" | "network" | "mixed"; "coverage": "complete" | "partial" | "unknown"; "fromCache": boolean } } };
  "academic.scores@1#getScores": { request: { "term"?: string; "courseType"?: string; "forceRefresh"?: boolean }; response: { "data": { "currentTerm"?: string; "availableTerms": Array<Record<string, unknown>>; "items": Array<Record<string, unknown>> }; "meta": { "syncedAt": string; "source": "cache" | "network" | "mixed"; "coverage": "complete" | "partial" | "unknown"; "fromCache": boolean } } };
  "academic.scores@1#getHistoryScores": { request: { "term"?: string; "forceRefresh"?: boolean }; response: { "data": { "currentTerm"?: string; "availableTerms": Array<Record<string, unknown>>; "items": Array<Record<string, unknown>> }; "meta": { "syncedAt": string; "source": "cache" | "network" | "mixed"; "coverage": "complete" | "partial" | "unknown"; "fromCache": boolean } } };
  "academic.exams@1#getExams": { request: { "term"?: string; "forceRefresh"?: boolean }; response: { "data": { "currentTerm"?: string; "availableTerms": Array<Record<string, unknown>>; "items": Array<Record<string, unknown>> }; "meta": { "syncedAt": string; "source": "cache" | "network" | "mixed"; "coverage": "complete" | "partial" | "unknown"; "fromCache": boolean } } };
  "academic.calendar@1#getCalendar": { request: { "month"?: string; "forceRefresh"?: boolean }; response: { "data": { "month": string; "currentWeek"?: string; "currentTerm"?: string; "availableTerms": Array<Record<string, unknown>>; "items": Array<Record<string, unknown>> }; "meta": { "syncedAt": string; "source": "cache" | "network" | "mixed"; "coverage": "complete" | "partial" | "unknown"; "fromCache": boolean } } };
  "academic.progress@1#getProgress": { request: { "forceRefresh"?: boolean }; response: { "data": { "currentTerm"?: string; "summary": Record<string, unknown>; "buckets": Array<Record<string, unknown>>; "mergedBuckets": Array<Record<string, unknown>>; "detailBuckets": Array<Record<string, unknown>>; "courses": Array<Record<string, unknown>>; "replaceCourses": Array<Record<string, unknown>>; "fields": Array<Record<string, unknown>> }; "meta": { "syncedAt": string; "source": "cache" | "network" | "mixed"; "coverage": "complete" | "partial" | "unknown"; "fromCache": boolean } } };
  "academic.homework@1#getHomework": { request: { "status"?: string; "forceRefresh"?: boolean }; response: { "data": { "currentTerm"?: string; "courses": Array<Record<string, unknown>>; "items": Array<Record<string, unknown>> }; "meta": { "syncedAt": string; "source": "cache" | "network" | "mixed"; "coverage": "complete" | "partial" | "unknown"; "fromCache": boolean } } };
  "academic.resources@1#getCourseResources": { request: { "term"?: string; "courseId": string; "folderId"?: string; "search"?: string; "categoryKey"?: string; "forceRefresh"?: boolean }; response: { "data": { "currentTerm"?: string; "courses": Array<Record<string, unknown>>; "selectedCourseId"?: number; "folderId": string; "categories": Array<Record<string, unknown>>; "selectedCategoryKey": string; "tree": Array<Record<string, unknown>>; "folders": Array<Record<string, unknown>>; "resources": Array<Record<string, unknown>> }; "meta": { "syncedAt": string; "source": "cache" | "network" | "mixed"; "coverage": "complete" | "partial" | "unknown"; "fromCache": boolean } } };
  "mail.read@1#listFolders": { request: { "forceRefresh"?: boolean }; response: { "data": { "folders": Array<Record<string, unknown>> }; "meta": { "syncedAt": string; "source": "cache" | "network" | "mixed"; "coverage": "complete" | "partial" | "unknown"; "fromCache": boolean } } };
  "mail.read@1#listMessages": { request: { "folderId"?: string; "start"?: number; "limit"?: number; "forceRefresh"?: boolean }; response: { "data": { "folderId": string; "start": number; "limit": number; "total": number; "messages": Array<Record<string, unknown>> }; "meta": { "syncedAt": string; "source": "cache" | "network" | "mixed"; "coverage": "complete" | "partial" | "unknown"; "fromCache": boolean } } };
  "mail.read@1#getMessage": { request: { "messageId": string; "mailbox"?: string }; response: { "data": { "messageId": string; "folderId": string; "subject": string; "fromText": string; "toText": string; "sender"?: string; "sentAt"?: string; "receivedAt"?: string; "modifiedAt"?: string; "size": number; "read": boolean; "attached": boolean; "priority"?: number; "summary"?: string; "fromList": Array<string>; "toList": Array<string>; "ccList": Array<string>; "bccList": Array<string>; "htmlContent": string; "headers": Record<string, unknown>; "attachments": Array<Record<string, unknown>> }; "meta": { "syncedAt": string; "source": "cache" | "network" | "mixed"; "coverage": "complete" | "partial" | "unknown"; "fromCache": boolean } } };
  "campus.request@1#request": { request: { "service": "mis" | "aa" | "ve"; "method"?: "GET" | "HEAD"; "path": string; "query"?: Record<string, unknown>; "accept"?: string }; response: { "data": unknown; "meta": { "syncedAt": string; "source": "cache" | "network" | "mixed"; "coverage": "complete" | "partial" | "unknown"; "fromCache": boolean } } };
  "network.request@1#request": { request: { "url": string; "method"?: "GET" | "HEAD" | "POST" | "PUT" | "PATCH" | "DELETE"; "headers"?: Record<string, unknown>; "body"?: unknown; "bodyType"?: "json" | "text" | "formData" | "blob"; "timeoutMs"?: number }; response: { "status": number; "headers": Record<string, unknown>; "bodyType": "json" | "text" | "resource"; "body"?: unknown; "resource"?: { "handle": string; "size": number; "contentType": string; "url": string; "etag"?: string; "pinned"?: boolean }; "finalUrl": string; "redirects": number; "contentType"?: string } };
  "storage.kv@2#get": { request: { "key": string }; response: { "value": unknown; "revision": number; [key: string]: unknown } };
  "storage.kv@2#set": { request: { "key": string; "value": unknown; "ifRevision"?: number }; response: { "revision": number; "usage": { "bytesUsed": number; "byteLimit": number; "keyCount": number; "keyLimit": number; "revision": number }; "changedKeys": Array<string> } };
  "storage.kv@2#remove": { request: { "key": string; "ifRevision"?: number }; response: { "removed": boolean; "revision": number; "usage": { "bytesUsed": number; "byteLimit": number; "keyCount": number; "keyLimit": number; "revision": number }; "changedKeys": Array<string> } };
  "storage.kv@2#keys": { request: Record<string, never>; response: { "keys": Array<string>; "revision": number; [key: string]: unknown } };
  "storage.kv@2#usage": { request: Record<string, never>; response: { "bytesUsed": number; "byteLimit": number; "keyCount": number; "keyLimit": number; "revision": number } };
  "storage.kv@2#batch": { request: { "operations": Array<Record<string, unknown>> }; response: { "revision": number; "usage": { "bytesUsed": number; "byteLimit": number; "keyCount": number; "keyLimit": number; "revision": number }; "changedKeys": Array<string> } };
  "storage.kv@2#transaction": { request: { "ifRevision": number; "operations": Array<Record<string, unknown>> }; response: { "revision": number; "usage": { "bytesUsed": number; "byteLimit": number; "keyCount": number; "keyLimit": number; "revision": number }; "changedKeys": Array<string> } };
  "storage.kv@2#export": { request: Record<string, never>; response: { "handle": string; "size": number; "contentType": string; "url": string; "etag"?: string; "pinned"?: boolean } };
  "storage.kv@2#import": { request: { "handle": string; "ifRevision"?: number }; response: { "revision": number; "usage": { "bytesUsed": number; "byteLimit": number; "keyCount": number; "keyLimit": number; "revision": number }; "changedKeys": Array<string> } };
  "storage.blob@1#put": { request: { "contentType": string; "size": number }; response: { "handle": string; "size": number; "contentType": string; "url": string; "etag"?: string; "pinned"?: boolean } };
  "storage.blob@1#getInfo": { request: { "handle": string }; response: { "handle": string; "size": number; "contentType": string; "url": string; "etag"?: string; "pinned"?: boolean } };
  "storage.blob@1#delete": { request: { "handle": string }; response: { "deleted": boolean } };
  "cache.resource@1#put": { request: { "key": string; "contentType": string; "size": number; "pin"?: boolean }; response: { "handle": string; "size": number; "contentType": string; "url": string; "etag"?: string; "pinned"?: boolean } };
  "cache.resource@1#promote": { request: { "handle": string; "key": string; "pinned"?: boolean }; response: { "handle": string; "size": number; "contentType": string; "url": string; "etag"?: string; "pinned"?: boolean } };
  "cache.resource@1#deleteHandle": { request: { "handle": string }; response: { "deleted": boolean } };
  "cache.resource@1#match": { request: { "key": string }; response: { "handle": string; "size": number; "contentType": string; "url": string; "etag"?: string; "pinned"?: boolean } | null };
  "cache.resource@1#delete": { request: { "key": string }; response: { "deleted": boolean } };
  "cache.resource@1#pin": { request: { "key": string; "pinned": boolean }; response: { "pinned": boolean } };
  "cache.resource@1#usage": { request: Record<string, never>; response: { "bytesUsed": number; "byteLimit": number; "globalByteLimit": number } };
  "android.accessibility.events@1#getStatus": { request: Record<string, never>; response: { "enabled": boolean; "connected": boolean; "subscriptionCount": number } };
  "android.accessibility.events@1#subscribe": { request: { "eventTypes": Array<"*" | "viewClicked" | "viewLongClicked" | "viewSelected" | "viewFocused" | "viewTextChanged" | "windowStateChanged" | "notificationStateChanged" | "viewHoverEnter" | "viewHoverExit" | "touchExplorationGestureStart" | "touchExplorationGestureEnd" | "windowContentChanged" | "viewScrolled" | "viewTextSelectionChanged" | "announcement" | "viewAccessibilityFocused" | "viewAccessibilityFocusCleared" | "viewTextTraversed" | "gestureDetectionStart" | "gestureDetectionEnd" | "touchInteractionStart" | "touchInteractionEnd" | "windowsChanged" | "viewContextClicked" | "assistReadingContext">; "packageNames"?: Array<string>; "persistent": boolean; "includeSource": boolean }; response: { "subscriptionId": string; "eventTypes": Array<string>; "packageNames": Array<string>; "persistent": boolean; "includeSource": boolean } };
  "android.accessibility.events@1#unsubscribe": { request: { "subscriptionId": string }; response: { "deleted": boolean } };
  "android.accessibility.events@1#listSubscriptions": { request: Record<string, never>; response: Array<{ "subscriptionId": string; "eventTypes": Array<string>; "packageNames": Array<string>; "persistent": boolean; "includeSource": boolean }> };
  "android.accessibility.nodes@1#getRoot": { request: { "windowId"?: number; "maxDepth"?: number; "maxNodes"?: number }; response: { "nodeId": string; "windowId": number; "className": string; "packageName": string; "viewIdResourceName"?: string; "text"?: string; "contentDescription"?: string; "bounds": { "left": number; "top": number; "right": number; "bottom": number }; "actions": Array<string>; "clickable"?: boolean; "enabled"?: boolean; "focused"?: boolean; "focusable"?: boolean; "scrollable"?: boolean; "selected"?: boolean; "checked"?: boolean; "password": boolean; "sensitive": boolean; "childCount"?: number; "children"?: Array<Record<string, unknown>> } };
  "android.accessibility.nodes@1#find": { request: { "windowId"?: number; "selector": { "className"?: string; "packageName"?: string; "viewIdResourceName"?: string; "text"?: string; "contentDescription"?: string; "clickable"?: boolean; "enabled"?: boolean; "focused"?: boolean; "focusable"?: boolean; "scrollable"?: boolean; "selected"?: boolean; "checked"?: boolean; "password"?: boolean; "sensitive"?: boolean }; "maxResults"?: number }; response: { "nodes": Array<{ "nodeId": string; "windowId": number; "className": string; "packageName": string; "viewIdResourceName"?: string; "text"?: string; "contentDescription"?: string; "bounds": { "left": number; "top": number; "right": number; "bottom": number }; "actions": Array<string>; "clickable"?: boolean; "enabled"?: boolean; "focused"?: boolean; "focusable"?: boolean; "scrollable"?: boolean; "selected"?: boolean; "checked"?: boolean; "password": boolean; "sensitive": boolean; "childCount"?: number; "children"?: Array<Record<string, unknown>> }>; "truncated": boolean } };
  "android.accessibility.nodes@1#get": { request: { "nodeId": string }; response: { "nodeId": string; "windowId": number; "className": string; "packageName": string; "viewIdResourceName"?: string; "text"?: string; "contentDescription"?: string; "bounds": { "left": number; "top": number; "right": number; "bottom": number }; "actions": Array<string>; "clickable"?: boolean; "enabled"?: boolean; "focused"?: boolean; "focusable"?: boolean; "scrollable"?: boolean; "selected"?: boolean; "checked"?: boolean; "password": boolean; "sensitive": boolean; "childCount"?: number; "children"?: Array<Record<string, unknown>> } };
  "android.accessibility.actions@1#performNode": { request: { "idempotencyKey": string; "nodeId": string; "action": "click" | "longClick" | "focus" | "clearFocus" | "select" | "clearSelection" | "scrollForward" | "scrollBackward" | "scrollUp" | "scrollDown" | "scrollLeft" | "scrollRight" | "expand" | "collapse" | "dismiss" | "showOnScreen" | "setText" | "setSelection" | "copy" | "paste"; "arguments"?: Record<string, unknown> }; response: { "receiptId": string; "idempotencyKey": string; "completedAt": string; "result": unknown } };
  "android.accessibility.actions@1#performGlobal": { request: { "idempotencyKey": string; "action": "back" | "home" | "recents" | "notifications" | "quickSettings" | "powerDialog" | "splitScreen" }; response: { "receiptId": string; "idempotencyKey": string; "completedAt": string; "result": unknown } };
  "android.accessibility.actions@1#dispatchGesture": { request: { "idempotencyKey": string; "strokes": Array<{ "points": Array<{ "x": number; "y": number }>; "startTimeMs"?: number; "durationMs": number }> }; response: { "receiptId": string; "idempotencyKey": string; "completedAt": string; "result": unknown } };
  "android.packages.read@1#list": { request: { "includeSystem"?: boolean; "includeDisabled"?: boolean }; response: { "packages": Array<{ "packageName": string; "label": string; "versionName": string; "versionCode": number; "uid": number; "enabled": boolean; "system": boolean; "firstInstallTime": number; "lastUpdateTime": number; "requestedPermissions": Array<string>; "grantedPermissions": Array<string>; "signingCertificates": Array<string>; "components": { "activities": Array<string>; "services": Array<string>; "receivers": Array<string>; "providers": Array<string> } }>; "truncated": boolean } };
  "android.packages.read@1#get": { request: { "packageName": string }; response: { "packageName": string; "label": string; "versionName": string; "versionCode": number; "uid": number; "enabled": boolean; "system": boolean; "firstInstallTime": number; "lastUpdateTime": number; "requestedPermissions": Array<string>; "grantedPermissions": Array<string>; "signingCertificates": Array<string>; "components": { "activities": Array<string>; "services": Array<string>; "receivers": Array<string>; "providers": Array<string> } } };
  "android.packages.read@1#resolveIntent": { request: { "action": string; "dataUri"?: string }; response: { "activities": Array<{ "packageName": string; "className": string; "exported": boolean }> } };
  "android.settings.open@1#open": { request: { "action": string; "packageName"?: string }; response: { "opened": boolean; "action": string } };
  "android.device.info@1#getInfo": { request: Record<string, never>; response: { "platform": unknown; "sdkInt": number; "manufacturer": string; "model": string; "locale": string; "timezone": string; "appVersion": string } };
  "android.network.status@1#getStatus": { request: Record<string, never>; response: { "online": boolean; "validated": boolean; "metered": boolean; "transport": "wifi" | "cellular" | "ethernet" | "vpn" | "other" | "none" } };
  "android.network.status@1#subscribe": { request: { "persistent"?: boolean }; response: { "subscriptionId": string; "persistent": boolean } };
  "android.network.status@1#unsubscribe": { request: { "subscriptionId": string }; response: { "deleted": boolean } };
  "android.battery.status@1#getStatus": { request: Record<string, never>; response: { "level": number; "charging": boolean; "status": "charging" | "discharging" | "full" | "notCharging" | "unknown" } };
  "android.battery.status@1#subscribe": { request: { "persistent"?: boolean }; response: { "subscriptionId": string; "persistent": boolean } };
  "android.battery.status@1#unsubscribe": { request: { "subscriptionId": string }; response: { "deleted": boolean } };
  "android.haptics.perform@1#perform": { request: { "durationMs": number }; response: { "performed": boolean; "durationMs": number } };
  "android.files.pick@1#pick": { request: { "mimeTypes"?: Array<string>; "multiple"?: boolean }; response: { "items": Array<{ "handle": string; "name": string; "mimeType": string; "size": number }> } };
  "android.files.save@1#save": { request: { "idempotencyKey": string; "handle": string; "fileName": string; "mimeType": string }; response: { "receiptId": string; "idempotencyKey": string; "completedAt": string; "result": unknown } };
  "android.media.pick@1#pick": { request: { "mediaType"?: "image" | "video" | "mixed"; "multiple"?: boolean }; response: { "items": Array<{ "handle": string; "name": string; "mimeType": string; "size": number }> } };
  "android.share.open@1#open": { request: { "title"?: string; "text"?: string; "url"?: string; "handle"?: string }; response: { "opened": boolean } };
  "android.notifications.post@1#getStatus": { request: Record<string, never>; response: { "granted": boolean; "enabled": boolean } };
  "android.notifications.post@1#show": { request: { "idempotencyKey": string; "id": string; "title": string; "body": string }; response: { "receiptId": string; "idempotencyKey": string; "completedAt": string; "result": unknown } };
  "android.notifications.post@1#schedule": { request: { "idempotencyKey": string; "id": string; "title": string; "body": string; "triggerAtMs": number }; response: { "receiptId": string; "idempotencyKey": string; "completedAt": string; "result": unknown } };
  "android.notifications.post@1#cancel": { request: { "idempotencyKey": string; "id": string }; response: { "receiptId": string; "idempotencyKey": string; "completedAt": string; "result": unknown } };
  "android.location.read@1#getStatus": { request: Record<string, never>; response: { "granted": boolean; "enabled": boolean } };
  "android.location.read@1#getCurrent": { request: { "highAccuracy"?: boolean; "timeoutMs"?: number }; response: { "latitude": number; "longitude": number; "accuracy"?: number; "time": number } };
  "android.calendar.read@1#list": { request: { "startMs": number; "endMs": number; "limit"?: number }; response: { "events": Array<{ "id": string; "title": string; "description"?: string; "location"?: string; "startMs": number; "endMs": number; "allDay": boolean }> } };
  "android.calendar.write@1#create": { request: { "idempotencyKey": string; "title": string; "description"?: string; "location"?: string; "startMs": number; "endMs": number; "allDay"?: boolean }; response: { "receiptId": string; "idempotencyKey": string; "completedAt": string; "result": unknown } };
  "android.calendar.write@1#update": { request: { "idempotencyKey": string; "id": string; "title"?: string; "description"?: string; "location"?: string; "startMs"?: number; "endMs"?: number; "allDay"?: boolean }; response: { "receiptId": string; "idempotencyKey": string; "completedAt": string; "result": unknown } };
  "android.calendar.write@1#delete": { request: { "idempotencyKey": string; "id": string }; response: { "receiptId": string; "idempotencyKey": string; "completedAt": string; "result": unknown } };
  "android.camera.capture@1#capturePhoto": { request: Record<string, never>; response: { "handle": string; "mimeType": unknown; "size": number } };
  "android.audio.record@1#start": { request: { "idempotencyKey": string; "recordingId": string }; response: { "receiptId": string; "idempotencyKey": string; "completedAt": string; "result": unknown } };
  "android.audio.record@1#stop": { request: { "idempotencyKey": string; "recordingId": string }; response: { "receiptId": string; "idempotencyKey": string; "completedAt": string; "result": unknown } };
  "android.sensors.read@1#list": { request: Record<string, never>; response: { "sensors": Array<"accelerometer" | "gyroscope" | "magneticField" | "light" | "pressure"> } };
  "android.sensors.read@1#subscribe": { request: { "sensor": "accelerometer" | "gyroscope" | "magneticField" | "light" | "pressure"; "persistent"?: boolean; "rateHz"?: number }; response: { "subscriptionId": string; "persistent": boolean; "rateHz": number } };
  "android.sensors.read@1#unsubscribe": { request: { "subscriptionId": string }; response: { "deleted": boolean } };
  "android.biometric.verify@1#getStatus": { request: Record<string, never>; response: { "available": boolean } };
  "android.biometric.verify@1#verify": { request: { "title": string; "subtitle"?: string }; response: { "verified": boolean } };
  "academic.userCourses.command@1#save": { request: { "idempotencyKey": string; "course": Record<string, unknown>; [key: string]: unknown }; response: { "receiptId": string; "idempotencyKey": string; "completedAt": string; "result": unknown } };
  "academic.userCourses.command@1#delete": { request: { "idempotencyKey": string; "id": number }; response: { "receiptId": string; "idempotencyKey": string; "completedAt": string; "result": unknown } };
  "academic.homework.submit@1#submit": { request: { "idempotencyKey": string; "homeworkId": number; "courseId": number; "content"?: string; "attachmentHandles"?: Array<string>; [key: string]: unknown }; response: { "receiptId": string; "idempotencyKey": string; "completedAt": string; "result": unknown } };
  "mail.send@1#send": { request: { "idempotencyKey": string; "to": Array<string>; "cc"?: Array<string>; "bcc"?: Array<string>; "subject": string; "text"?: string; "html"?: string; "attachmentHandles"?: Array<string>; [key: string]: unknown }; response: { "receiptId": string; "idempotencyKey": string; "completedAt": string; "result": unknown } };
  "android.session.keepAlive@1#acquire": { request: { "idempotencyKey": string; "requestedDurationMs": number }; response: { "receiptId": string; "idempotencyKey": string; "completedAt": string; "result": { "lease"?: { "leaseId": string; "expiresAtMs": number; "maxExpiresAtMs": number }; "released": boolean } } };
  "android.session.keepAlive@1#renew": { request: { "idempotencyKey": string; "leaseId": string; "requestedDurationMs": number }; response: { "receiptId": string; "idempotencyKey": string; "completedAt": string; "result": { "lease"?: { "leaseId": string; "expiresAtMs": number; "maxExpiresAtMs": number }; "released": boolean } } };
  "android.session.keepAlive@1#release": { request: { "idempotencyKey": string; "leaseId": string }; response: { "receiptId": string; "idempotencyKey": string; "completedAt": string; "result": { "lease"?: { "leaseId": string; "expiresAtMs": number; "maxExpiresAtMs": number }; "released": boolean } } };
  "android.session.keepAlive@1#getStatus": { request: Record<string, never>; response: { "active": boolean; "serviceState": "stopped" | "pending" | "running" | "degraded"; "leases": Array<{ "leaseId": string; "expiresAtMs": number; "maxExpiresAtMs": number }> } };
}

export interface CapabilityEventMap {
  "runtime.lifecycle@1#resume": { data: Record<string, never>; requiresAcknowledgement: false };
  "runtime.lifecycle@1#pause": { data: Record<string, never>; requiresAcknowledgement: false };
  "runtime.lifecycle@1#theme": { data: { "colorScheme": "light" | "dark"; "reducedMotion": boolean; "highContrast": boolean }; requiresAcknowledgement: false };
  "runtime.lifecycle@1#resize": { data: { "viewportWidthPx": number; "viewportHeightPx": number; "density": number; "fontScale": number; "orientation": "portrait" | "landscape"; "safeAreaTopPx": number; "safeAreaRightPx": number; "safeAreaBottomPx": number; "safeAreaLeftPx": number; "imeHeightPx": number }; requiresAcknowledgement: false };
  "runtime.lifecycle@1#network": { data: { "online": boolean; "validated": boolean; "metered": boolean; "transport": string }; requiresAcknowledgement: false };
  "runtime.lifecycle@1#back": { data: Record<string, never>; requiresAcknowledgement: true };
  "network.request@1#progress": { data: { "loaded": number; "total"?: number; "phase": "upload" | "response" }; requiresAcknowledgement: false };
  "storage.kv@2#changed": { data: { "revision": number; "keys": Array<string>; "cleared": boolean }; requiresAcknowledgement: false };
  "android.accessibility.events@1#received": { data: { "subscriptionId": string; "eventType": string; "packageName": string; "className": string; "eventTime": number; "text"?: Array<string>; "contentDescription"?: string; "source": { "nodeId": string; "windowId": number; "className": string; "packageName": string; "viewIdResourceName"?: string; "text"?: string; "contentDescription"?: string; "bounds": { "left": number; "top": number; "right": number; "bottom": number }; "actions": Array<string>; "clickable"?: boolean; "enabled"?: boolean; "focused"?: boolean; "focusable"?: boolean; "scrollable"?: boolean; "selected"?: boolean; "checked"?: boolean; "password": boolean; "sensitive": boolean; "childCount"?: number; "children"?: Array<Record<string, unknown>> } | null }; requiresAcknowledgement: false };
  "android.network.status@1#changed": { data: { "online": boolean; "validated": boolean; "metered": boolean; "transport": "wifi" | "cellular" | "ethernet" | "vpn" | "other" | "none" }; requiresAcknowledgement: false };
  "android.battery.status@1#changed": { data: { "level": number; "charging": boolean; "status": "charging" | "discharging" | "full" | "notCharging" | "unknown" }; requiresAcknowledgement: false };
  "android.sensors.read@1#changed": { data: { "subscriptionId": string; "sensor": "accelerometer" | "gyroscope" | "magneticField" | "light" | "pressure"; "values": Array<number>; "timestampMs": number }; requiresAcknowledgement: false };
  "android.session.keepAlive@1#ended": { data: { "leaseId": string; "reason": "released" | "expired" | "revoked" | "clock_changed" | "user_stopped" | "max_runtime" | "session_unavailable" | "service_unavailable" }; requiresAcknowledgement: false };
}

export type CapabilityRoute = keyof CapabilityMethodMap;
export type CapabilityRequest<Route extends CapabilityRoute> = CapabilityMethodMap[Route]["request"];
export type CapabilityResponse<Route extends CapabilityRoute> = CapabilityMethodMap[Route]["response"];
export type CapabilityEventRoute = keyof CapabilityEventMap;
export type CapabilityEventData<Route extends CapabilityEventRoute> = CapabilityEventMap[Route]["data"];
export type CapabilityEventAcknowledgement<Route extends CapabilityEventRoute> = CapabilityEventMap[Route]["requiresAcknowledgement"];

export const CAPABILITY_MOCK_RESPONSES = {
  "runtime.lifecycle@1#handshake": {
    "protocolVersion": 0,
    "contractProfile": "example",
    "runtimeFloor": 0,
    "availableCapabilities": [],
    "binaryTransports": []
  },
  "runtime.lifecycle@1#ready": {
    "ready": false
  },
  "runtime.lifecycle@1#close": {
    "closed": false
  },
  "configuration.read@1#get": {
    "value": "example"
  },
  "navigation.external@1#open": {
    "opened": false
  },
  "identity.profile@1#getProfile": {
    "data": {
      "fields": [],
      "sections": []
    },
    "meta": {
      "syncedAt": "example",
      "source": "cache",
      "coverage": "complete",
      "fromCache": false
    }
  },
  "academic.timetable@1#getTimetable": {
    "data": {
      "days": [],
      "periods": [],
      "entries": [],
      "availableTerms": []
    },
    "meta": {
      "syncedAt": "example",
      "source": "cache",
      "coverage": "complete",
      "fromCache": false
    }
  },
  "academic.scores@1#getScores": {
    "data": {
      "availableTerms": [],
      "items": []
    },
    "meta": {
      "syncedAt": "example",
      "source": "cache",
      "coverage": "complete",
      "fromCache": false
    }
  },
  "academic.scores@1#getHistoryScores": {
    "data": {
      "availableTerms": [],
      "items": []
    },
    "meta": {
      "syncedAt": "example",
      "source": "cache",
      "coverage": "complete",
      "fromCache": false
    }
  },
  "academic.exams@1#getExams": {
    "data": {
      "availableTerms": [],
      "items": []
    },
    "meta": {
      "syncedAt": "example",
      "source": "cache",
      "coverage": "complete",
      "fromCache": false
    }
  },
  "academic.calendar@1#getCalendar": {
    "data": {
      "month": "example",
      "availableTerms": [],
      "items": []
    },
    "meta": {
      "syncedAt": "example",
      "source": "cache",
      "coverage": "complete",
      "fromCache": false
    }
  },
  "academic.progress@1#getProgress": {
    "data": {
      "summary": {},
      "buckets": [],
      "mergedBuckets": [],
      "detailBuckets": [],
      "courses": [],
      "replaceCourses": [],
      "fields": []
    },
    "meta": {
      "syncedAt": "example",
      "source": "cache",
      "coverage": "complete",
      "fromCache": false
    }
  },
  "academic.homework@1#getHomework": {
    "data": {
      "courses": [],
      "items": []
    },
    "meta": {
      "syncedAt": "example",
      "source": "cache",
      "coverage": "complete",
      "fromCache": false
    }
  },
  "academic.resources@1#getCourseResources": {
    "data": {
      "courses": [],
      "folderId": "example",
      "categories": [],
      "selectedCategoryKey": "example",
      "tree": [],
      "folders": [],
      "resources": []
    },
    "meta": {
      "syncedAt": "example",
      "source": "cache",
      "coverage": "complete",
      "fromCache": false
    }
  },
  "mail.read@1#listFolders": {
    "data": {
      "folders": []
    },
    "meta": {
      "syncedAt": "example",
      "source": "cache",
      "coverage": "complete",
      "fromCache": false
    }
  },
  "mail.read@1#listMessages": {
    "data": {
      "folderId": "example",
      "start": 0,
      "limit": 0,
      "total": 0,
      "messages": []
    },
    "meta": {
      "syncedAt": "example",
      "source": "cache",
      "coverage": "complete",
      "fromCache": false
    }
  },
  "mail.read@1#getMessage": {
    "data": {
      "messageId": "example",
      "folderId": "example",
      "subject": "example",
      "fromText": "example",
      "toText": "example",
      "size": 0,
      "read": false,
      "attached": false,
      "fromList": [],
      "toList": [],
      "ccList": [],
      "bccList": [],
      "htmlContent": "example",
      "headers": {},
      "attachments": []
    },
    "meta": {
      "syncedAt": "example",
      "source": "cache",
      "coverage": "complete",
      "fromCache": false
    }
  },
  "campus.request@1#request": {
    "data": null,
    "meta": {
      "syncedAt": "example",
      "source": "cache",
      "coverage": "complete",
      "fromCache": false
    }
  },
  "network.request@1#request": {
    "status": 0,
    "headers": {},
    "bodyType": "json",
    "finalUrl": "example",
    "redirects": 0
  },
  "storage.kv@2#get": {
    "value": null,
    "revision": 0
  },
  "storage.kv@2#set": {
    "revision": 0,
    "usage": {
      "bytesUsed": 0,
      "byteLimit": 0,
      "keyCount": 0,
      "keyLimit": 0,
      "revision": 0
    },
    "changedKeys": []
  },
  "storage.kv@2#remove": {
    "removed": false,
    "revision": 0,
    "usage": {
      "bytesUsed": 0,
      "byteLimit": 0,
      "keyCount": 0,
      "keyLimit": 0,
      "revision": 0
    },
    "changedKeys": []
  },
  "storage.kv@2#keys": {
    "keys": [],
    "revision": 0
  },
  "storage.kv@2#usage": {
    "bytesUsed": 0,
    "byteLimit": 0,
    "keyCount": 0,
    "keyLimit": 0,
    "revision": 0
  },
  "storage.kv@2#batch": {
    "revision": 0,
    "usage": {
      "bytesUsed": 0,
      "byteLimit": 0,
      "keyCount": 0,
      "keyLimit": 0,
      "revision": 0
    },
    "changedKeys": []
  },
  "storage.kv@2#transaction": {
    "revision": 0,
    "usage": {
      "bytesUsed": 0,
      "byteLimit": 0,
      "keyCount": 0,
      "keyLimit": 0,
      "revision": 0
    },
    "changedKeys": []
  },
  "storage.kv@2#export": {
    "handle": "example",
    "size": 0,
    "contentType": "example",
    "url": "example"
  },
  "storage.kv@2#import": {
    "revision": 0,
    "usage": {
      "bytesUsed": 0,
      "byteLimit": 0,
      "keyCount": 0,
      "keyLimit": 0,
      "revision": 0
    },
    "changedKeys": []
  },
  "storage.blob@1#put": {
    "handle": "example",
    "size": 0,
    "contentType": "example",
    "url": "example"
  },
  "storage.blob@1#getInfo": {
    "handle": "example",
    "size": 0,
    "contentType": "example",
    "url": "example"
  },
  "storage.blob@1#delete": {
    "deleted": false
  },
  "cache.resource@1#put": {
    "handle": "example",
    "size": 0,
    "contentType": "example",
    "url": "example"
  },
  "cache.resource@1#promote": {
    "handle": "example",
    "size": 0,
    "contentType": "example",
    "url": "example"
  },
  "cache.resource@1#deleteHandle": {
    "deleted": false
  },
  "cache.resource@1#match": {
    "handle": "example",
    "size": 0,
    "contentType": "example",
    "url": "example"
  },
  "cache.resource@1#delete": {
    "deleted": false
  },
  "cache.resource@1#pin": {
    "pinned": false
  },
  "cache.resource@1#usage": {
    "bytesUsed": 0,
    "byteLimit": 0,
    "globalByteLimit": 0
  },
  "android.accessibility.events@1#getStatus": {
    "enabled": false,
    "connected": false,
    "subscriptionCount": 0
  },
  "android.accessibility.events@1#subscribe": {
    "subscriptionId": "example",
    "eventTypes": [
      "example"
    ],
    "persistent": false,
    "packageNames": [],
    "includeSource": false
  },
  "android.accessibility.events@1#unsubscribe": {
    "deleted": false
  },
  "android.accessibility.events@1#listSubscriptions": [],
  "android.accessibility.nodes@1#getRoot": {
    "nodeId": "example",
    "windowId": 0,
    "className": "example",
    "packageName": "example",
    "bounds": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 0
    },
    "actions": [],
    "password": false,
    "sensitive": false
  },
  "android.accessibility.nodes@1#find": {
    "nodes": [],
    "truncated": false
  },
  "android.accessibility.nodes@1#get": {
    "nodeId": "example",
    "windowId": 0,
    "className": "example",
    "packageName": "example",
    "bounds": {
      "left": 0,
      "top": 0,
      "right": 0,
      "bottom": 0
    },
    "actions": [],
    "password": false,
    "sensitive": false
  },
  "android.accessibility.actions@1#performNode": {
    "receiptId": "example",
    "idempotencyKey": "example",
    "completedAt": "example",
    "result": null
  },
  "android.accessibility.actions@1#performGlobal": {
    "receiptId": "example",
    "idempotencyKey": "example",
    "completedAt": "example",
    "result": null
  },
  "android.accessibility.actions@1#dispatchGesture": {
    "receiptId": "example",
    "idempotencyKey": "example",
    "completedAt": "example",
    "result": null
  },
  "android.packages.read@1#list": {
    "packages": [],
    "truncated": false
  },
  "android.packages.read@1#get": {
    "packageName": "example",
    "label": "example",
    "versionName": "example",
    "versionCode": 0,
    "uid": 0,
    "enabled": false,
    "system": false,
    "firstInstallTime": 0,
    "lastUpdateTime": 0,
    "requestedPermissions": [],
    "grantedPermissions": [],
    "signingCertificates": [],
    "components": {
      "activities": [],
      "services": [],
      "receivers": [],
      "providers": []
    }
  },
  "android.packages.read@1#resolveIntent": {
    "activities": []
  },
  "android.settings.open@1#open": {
    "opened": false,
    "action": "example"
  },
  "android.device.info@1#getInfo": {
    "platform": "android",
    "sdkInt": 0,
    "manufacturer": "example",
    "model": "example",
    "locale": "example",
    "timezone": "example",
    "appVersion": "example"
  },
  "android.network.status@1#getStatus": {
    "online": false,
    "validated": false,
    "metered": false,
    "transport": "wifi"
  },
  "android.network.status@1#subscribe": {
    "subscriptionId": "example",
    "persistent": false
  },
  "android.network.status@1#unsubscribe": {
    "deleted": false
  },
  "android.battery.status@1#getStatus": {
    "level": 0,
    "charging": false,
    "status": "charging"
  },
  "android.battery.status@1#subscribe": {
    "subscriptionId": "example",
    "persistent": false
  },
  "android.battery.status@1#unsubscribe": {
    "deleted": false
  },
  "android.haptics.perform@1#perform": {
    "performed": false,
    "durationMs": 0
  },
  "android.files.pick@1#pick": {
    "items": []
  },
  "android.files.save@1#save": {
    "receiptId": "example",
    "idempotencyKey": "example",
    "completedAt": "example",
    "result": null
  },
  "android.media.pick@1#pick": {
    "items": []
  },
  "android.share.open@1#open": {
    "opened": false
  },
  "android.notifications.post@1#getStatus": {
    "granted": false,
    "enabled": false
  },
  "android.notifications.post@1#show": {
    "receiptId": "example",
    "idempotencyKey": "example",
    "completedAt": "example",
    "result": null
  },
  "android.notifications.post@1#schedule": {
    "receiptId": "example",
    "idempotencyKey": "example",
    "completedAt": "example",
    "result": null
  },
  "android.notifications.post@1#cancel": {
    "receiptId": "example",
    "idempotencyKey": "example",
    "completedAt": "example",
    "result": null
  },
  "android.location.read@1#getStatus": {
    "granted": false,
    "enabled": false
  },
  "android.location.read@1#getCurrent": {
    "latitude": 0,
    "longitude": 0,
    "time": 0
  },
  "android.calendar.read@1#list": {
    "events": []
  },
  "android.calendar.write@1#create": {
    "receiptId": "example",
    "idempotencyKey": "example",
    "completedAt": "example",
    "result": null
  },
  "android.calendar.write@1#update": {
    "receiptId": "example",
    "idempotencyKey": "example",
    "completedAt": "example",
    "result": null
  },
  "android.calendar.write@1#delete": {
    "receiptId": "example",
    "idempotencyKey": "example",
    "completedAt": "example",
    "result": null
  },
  "android.camera.capture@1#capturePhoto": {
    "handle": "example",
    "mimeType": "image/jpeg",
    "size": 0
  },
  "android.audio.record@1#start": {
    "receiptId": "example",
    "idempotencyKey": "example",
    "completedAt": "example",
    "result": null
  },
  "android.audio.record@1#stop": {
    "receiptId": "example",
    "idempotencyKey": "example",
    "completedAt": "example",
    "result": null
  },
  "android.sensors.read@1#list": {
    "sensors": []
  },
  "android.sensors.read@1#subscribe": {
    "subscriptionId": "example",
    "persistent": false,
    "rateHz": 1
  },
  "android.sensors.read@1#unsubscribe": {
    "deleted": false
  },
  "android.biometric.verify@1#getStatus": {
    "available": false
  },
  "android.biometric.verify@1#verify": {
    "verified": false
  },
  "academic.userCourses.command@1#save": {
    "receiptId": "example",
    "idempotencyKey": "example",
    "completedAt": "example",
    "result": null
  },
  "academic.userCourses.command@1#delete": {
    "receiptId": "example",
    "idempotencyKey": "example",
    "completedAt": "example",
    "result": null
  },
  "academic.homework.submit@1#submit": {
    "receiptId": "example",
    "idempotencyKey": "example",
    "completedAt": "example",
    "result": null
  },
  "mail.send@1#send": {
    "receiptId": "example",
    "idempotencyKey": "example",
    "completedAt": "example",
    "result": null
  },
  "android.session.keepAlive@1#acquire": {
    "receiptId": "example",
    "idempotencyKey": "example",
    "completedAt": "example",
    "result": {
      "released": false
    }
  },
  "android.session.keepAlive@1#renew": {
    "receiptId": "example",
    "idempotencyKey": "example",
    "completedAt": "example",
    "result": {
      "released": false
    }
  },
  "android.session.keepAlive@1#release": {
    "receiptId": "example",
    "idempotencyKey": "example",
    "completedAt": "example",
    "result": {
      "released": false
    }
  },
  "android.session.keepAlive@1#getStatus": {
    "active": false,
    "serviceState": "stopped",
    "leases": []
  }
} as const;

export const CAPABILITY_REGISTRY = {
  "$schema": "./capability-contracts.schema.json",
  "contractProfile": "contract_v1",
  "schemaVersion": 3,
  "protocolVersion": 2,
  "runtimeFloor": 2,
  "packageLimits": {
    "archiveBytes": 26214400,
    "extractedBytes": 52428800,
    "files": 1000,
    "iconBytes": 1048576
  },
  "errors": [
    "permission_denied",
    "capability_unavailable",
    "invalid_request",
    "origin_denied",
    "network_timeout",
    "request_timeout",
    "http_error",
    "quota_exceeded",
    "resource_too_large",
    "migration_failed",
    "user_cancelled",
    "foreground_required",
    "idempotency_conflict",
    "lease_not_found",
    "foreground_service_denied",
    "notifications_unavailable"
  ],
  "marketplaceCategories": [
    "academic",
    "campus",
    "information",
    "productivity",
    "assistant",
    "other"
  ],
  "configurationTypes": [
    "text",
    "secret",
    "url",
    "number",
    "boolean",
    "select"
  ],
  "schemas": {
    "empty": {
      "type": "object",
      "additionalProperties": false
    },
    "readOptions": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "forceRefresh": {
          "type": "boolean"
        }
      }
    },
    "campusReadMeta": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "syncedAt",
        "source",
        "coverage",
        "fromCache"
      ],
      "properties": {
        "syncedAt": {
          "type": "string"
        },
        "source": {
          "type": "string",
          "enum": [
            "cache",
            "network",
            "mixed"
          ]
        },
        "coverage": {
          "type": "string",
          "enum": [
            "complete",
            "partial",
            "unknown"
          ]
        },
        "fromCache": {
          "type": "boolean"
        }
      }
    },
    "campusRead": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "data",
        "meta"
      ],
      "properties": {
        "data": {},
        "meta": {
          "$ref": "#/schemas/campusReadMeta"
        }
      }
    },
    "studentProfileRead": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "data",
        "meta"
      ],
      "properties": {
        "data": {
          "type": "object",
          "additionalProperties": false,
          "required": [
            "fields",
            "sections"
          ],
          "properties": {
            "name": {
              "type": "string"
            },
            "studentId": {
              "type": "string"
            },
            "account": {
              "type": "string"
            },
            "gender": {
              "type": "string"
            },
            "birthday": {
              "type": "string"
            },
            "college": {
              "type": "string"
            },
            "major": {
              "type": "string"
            },
            "className": {
              "type": "string"
            },
            "grade": {
              "type": "string"
            },
            "educationLevel": {
              "type": "string"
            },
            "studentStatus": {
              "type": "string"
            },
            "campus": {
              "type": "string"
            },
            "phone": {
              "type": "string"
            },
            "email": {
              "type": "string"
            },
            "avatarUrl": {
              "type": "string"
            },
            "fields": {
              "type": "array",
              "items": {
                "type": "object"
              }
            },
            "sections": {
              "type": "array",
              "items": {
                "type": "object"
              }
            }
          }
        },
        "meta": {
          "$ref": "#/schemas/campusReadMeta"
        }
      }
    },
    "timetableRead": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "data",
        "meta"
      ],
      "properties": {
        "data": {
          "type": "object",
          "additionalProperties": false,
          "required": [
            "days",
            "periods",
            "entries",
            "availableTerms"
          ],
          "properties": {
            "days": {
              "type": "array",
              "items": {
                "type": "string"
              }
            },
            "periods": {
              "type": "array",
              "items": {
                "type": "string"
              }
            },
            "entries": {
              "type": "array",
              "items": {
                "type": "object"
              }
            },
            "currentTerm": {
              "type": "string"
            },
            "availableTerms": {
              "type": "array",
              "items": {
                "type": "object"
              }
            }
          }
        },
        "meta": {
          "$ref": "#/schemas/campusReadMeta"
        }
      }
    },
    "scoreRead": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "data",
        "meta"
      ],
      "properties": {
        "data": {
          "type": "object",
          "additionalProperties": false,
          "required": [
            "availableTerms",
            "items"
          ],
          "properties": {
            "currentTerm": {
              "type": "string"
            },
            "availableTerms": {
              "type": "array",
              "items": {
                "type": "object"
              }
            },
            "items": {
              "type": "array",
              "items": {
                "type": "object"
              }
            }
          }
        },
        "meta": {
          "$ref": "#/schemas/campusReadMeta"
        }
      }
    },
    "examRead": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "data",
        "meta"
      ],
      "properties": {
        "data": {
          "type": "object",
          "additionalProperties": false,
          "required": [
            "availableTerms",
            "items"
          ],
          "properties": {
            "currentTerm": {
              "type": "string"
            },
            "availableTerms": {
              "type": "array",
              "items": {
                "type": "object"
              }
            },
            "items": {
              "type": "array",
              "items": {
                "type": "object"
              }
            }
          }
        },
        "meta": {
          "$ref": "#/schemas/campusReadMeta"
        }
      }
    },
    "calendarRead": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "data",
        "meta"
      ],
      "properties": {
        "data": {
          "type": "object",
          "additionalProperties": false,
          "required": [
            "month",
            "availableTerms",
            "items"
          ],
          "properties": {
            "month": {
              "type": "string"
            },
            "currentWeek": {
              "type": "string"
            },
            "currentTerm": {
              "type": "string"
            },
            "availableTerms": {
              "type": "array",
              "items": {
                "type": "object"
              }
            },
            "items": {
              "type": "array",
              "items": {
                "type": "object"
              }
            }
          }
        },
        "meta": {
          "$ref": "#/schemas/campusReadMeta"
        }
      }
    },
    "academicProgressRead": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "data",
        "meta"
      ],
      "properties": {
        "data": {
          "type": "object",
          "additionalProperties": false,
          "required": [
            "summary",
            "buckets",
            "mergedBuckets",
            "detailBuckets",
            "courses",
            "replaceCourses",
            "fields"
          ],
          "properties": {
            "currentTerm": {
              "type": "string"
            },
            "summary": {
              "type": "object"
            },
            "buckets": {
              "type": "array",
              "items": {
                "type": "object"
              }
            },
            "mergedBuckets": {
              "type": "array",
              "items": {
                "type": "object"
              }
            },
            "detailBuckets": {
              "type": "array",
              "items": {
                "type": "object"
              }
            },
            "courses": {
              "type": "array",
              "items": {
                "type": "object"
              }
            },
            "replaceCourses": {
              "type": "array",
              "items": {
                "type": "object"
              }
            },
            "fields": {
              "type": "array",
              "items": {
                "type": "object"
              }
            }
          }
        },
        "meta": {
          "$ref": "#/schemas/campusReadMeta"
        }
      }
    },
    "homeworkRead": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "data",
        "meta"
      ],
      "properties": {
        "data": {
          "type": "object",
          "additionalProperties": false,
          "required": [
            "courses",
            "items"
          ],
          "properties": {
            "currentTerm": {
              "type": "string"
            },
            "courses": {
              "type": "array",
              "items": {
                "type": "object"
              }
            },
            "items": {
              "type": "array",
              "items": {
                "type": "object"
              }
            }
          }
        },
        "meta": {
          "$ref": "#/schemas/campusReadMeta"
        }
      }
    },
    "courseResourcesRead": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "data",
        "meta"
      ],
      "properties": {
        "data": {
          "type": "object",
          "additionalProperties": false,
          "required": [
            "courses",
            "folderId",
            "categories",
            "selectedCategoryKey",
            "tree",
            "folders",
            "resources"
          ],
          "properties": {
            "currentTerm": {
              "type": "string"
            },
            "courses": {
              "type": "array",
              "items": {
                "type": "object"
              }
            },
            "selectedCourseId": {
              "type": "integer"
            },
            "folderId": {
              "type": "string"
            },
            "categories": {
              "type": "array",
              "items": {
                "type": "object"
              }
            },
            "selectedCategoryKey": {
              "type": "string"
            },
            "tree": {
              "type": "array",
              "items": {
                "type": "object"
              }
            },
            "folders": {
              "type": "array",
              "items": {
                "type": "object"
              }
            },
            "resources": {
              "type": "array",
              "items": {
                "type": "object"
              }
            }
          }
        },
        "meta": {
          "$ref": "#/schemas/campusReadMeta"
        }
      }
    },
    "mailFoldersRead": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "data",
        "meta"
      ],
      "properties": {
        "data": {
          "type": "object",
          "additionalProperties": false,
          "required": [
            "folders"
          ],
          "properties": {
            "folders": {
              "type": "array",
              "items": {
                "type": "object"
              }
            }
          }
        },
        "meta": {
          "$ref": "#/schemas/campusReadMeta"
        }
      }
    },
    "mailMessagesRead": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "data",
        "meta"
      ],
      "properties": {
        "data": {
          "type": "object",
          "additionalProperties": false,
          "required": [
            "folderId",
            "start",
            "limit",
            "total",
            "messages"
          ],
          "properties": {
            "folderId": {
              "type": "string"
            },
            "start": {
              "type": "integer"
            },
            "limit": {
              "type": "integer"
            },
            "total": {
              "type": "integer"
            },
            "messages": {
              "type": "array",
              "items": {
                "type": "object"
              }
            }
          }
        },
        "meta": {
          "$ref": "#/schemas/campusReadMeta"
        }
      }
    },
    "mailMessageRead": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "data",
        "meta"
      ],
      "properties": {
        "data": {
          "type": "object",
          "additionalProperties": false,
          "required": [
            "messageId",
            "folderId",
            "subject",
            "fromText",
            "toText",
            "size",
            "read",
            "attached",
            "fromList",
            "toList",
            "ccList",
            "bccList",
            "htmlContent",
            "headers",
            "attachments"
          ],
          "properties": {
            "messageId": {
              "type": "string"
            },
            "folderId": {
              "type": "string"
            },
            "subject": {
              "type": "string"
            },
            "fromText": {
              "type": "string"
            },
            "toText": {
              "type": "string"
            },
            "sender": {
              "type": "string"
            },
            "sentAt": {
              "type": "string"
            },
            "receivedAt": {
              "type": "string"
            },
            "modifiedAt": {
              "type": "string"
            },
            "size": {
              "type": "integer"
            },
            "read": {
              "type": "boolean"
            },
            "attached": {
              "type": "boolean"
            },
            "priority": {
              "type": "integer"
            },
            "summary": {
              "type": "string"
            },
            "fromList": {
              "type": "array",
              "items": {
                "type": "string"
              }
            },
            "toList": {
              "type": "array",
              "items": {
                "type": "string"
              }
            },
            "ccList": {
              "type": "array",
              "items": {
                "type": "string"
              }
            },
            "bccList": {
              "type": "array",
              "items": {
                "type": "string"
              }
            },
            "htmlContent": {
              "type": "string"
            },
            "headers": {
              "type": "object"
            },
            "attachments": {
              "type": "array",
              "items": {
                "type": "object"
              }
            }
          }
        },
        "meta": {
          "$ref": "#/schemas/campusReadMeta"
        }
      }
    },
    "commandReceipt": {
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
          "type": "string"
        },
        "idempotencyKey": {
          "type": "string"
        },
        "completedAt": {
          "type": "string"
        },
        "result": {}
      }
    },
    "kvUsage": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "bytesUsed",
        "byteLimit",
        "keyCount",
        "keyLimit",
        "revision"
      ],
      "properties": {
        "bytesUsed": {
          "type": "integer",
          "minimum": 0
        },
        "byteLimit": {
          "type": "integer",
          "minimum": 0
        },
        "keyCount": {
          "type": "integer",
          "minimum": 0
        },
        "keyLimit": {
          "type": "integer",
          "minimum": 0
        },
        "revision": {
          "type": "integer",
          "minimum": 0
        }
      }
    },
    "kvTransaction": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "revision",
        "usage",
        "changedKeys"
      ],
      "properties": {
        "revision": {
          "type": "integer",
          "minimum": 0
        },
        "usage": {
          "$ref": "#/schemas/kvUsage"
        },
        "changedKeys": {
          "type": "array",
          "items": {
            "type": "string"
          }
        }
      }
    },
    "kvRemove": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "removed",
        "revision",
        "usage",
        "changedKeys"
      ],
      "properties": {
        "removed": {
          "type": "boolean"
        },
        "revision": {
          "type": "integer",
          "minimum": 0
        },
        "usage": {
          "$ref": "#/schemas/kvUsage"
        },
        "changedKeys": {
          "type": "array",
          "items": {
            "type": "string"
          }
        }
      }
    },
    "cacheUsage": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "bytesUsed",
        "byteLimit",
        "globalByteLimit"
      ],
      "properties": {
        "bytesUsed": {
          "type": "integer",
          "minimum": 0
        },
        "byteLimit": {
          "type": "integer",
          "minimum": 0
        },
        "globalByteLimit": {
          "type": "integer",
          "minimum": 0
        }
      }
    },
    "resourceHandle": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "handle",
        "size",
        "contentType",
        "url"
      ],
      "properties": {
        "handle": {
          "type": "string"
        },
        "size": {
          "type": "integer",
          "minimum": 0
        },
        "contentType": {
          "type": "string"
        },
        "url": {
          "type": "string"
        },
        "etag": {
          "type": "string"
        },
        "pinned": {
          "type": "boolean"
        }
      }
    },
    "resourceHandleOrNull": {
      "type": [
        "object",
        "null"
      ],
      "additionalProperties": false,
      "required": [
        "handle",
        "size",
        "contentType",
        "url"
      ],
      "properties": {
        "handle": {
          "type": "string"
        },
        "size": {
          "type": "integer",
          "minimum": 0
        },
        "contentType": {
          "type": "string"
        },
        "url": {
          "type": "string"
        },
        "etag": {
          "type": "string"
        },
        "pinned": {
          "type": "boolean"
        }
      }
    },
    "deletionResult": {
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
    },
    "pinResult": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "pinned"
      ],
      "properties": {
        "pinned": {
          "type": "boolean"
        }
      }
    },
    "accessibilityStatus": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "enabled",
        "connected",
        "subscriptionCount"
      ],
      "properties": {
        "enabled": {
          "type": "boolean"
        },
        "connected": {
          "type": "boolean"
        },
        "subscriptionCount": {
          "type": "integer",
          "minimum": 0
        }
      }
    },
    "accessibilitySubscription": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "subscriptionId",
        "eventTypes",
        "persistent",
        "packageNames",
        "includeSource"
      ],
      "properties": {
        "subscriptionId": {
          "type": "string",
          "minLength": 1,
          "maxLength": 128
        },
        "eventTypes": {
          "type": "array",
          "minItems": 1,
          "maxItems": 32,
          "items": {
            "type": "string",
            "minLength": 1,
            "maxLength": 80
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
    },
    "accessibilityNode": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "nodeId",
        "windowId",
        "className",
        "packageName",
        "bounds",
        "actions",
        "password",
        "sensitive"
      ],
      "properties": {
        "nodeId": {
          "type": "string",
          "minLength": 1,
          "maxLength": 160
        },
        "windowId": {
          "type": "integer"
        },
        "className": {
          "type": "string",
          "maxLength": 512
        },
        "packageName": {
          "type": "string",
          "maxLength": 256
        },
        "viewIdResourceName": {
          "type": "string",
          "maxLength": 512
        },
        "text": {
          "type": "string",
          "maxLength": 4096
        },
        "contentDescription": {
          "type": "string",
          "maxLength": 4096
        },
        "bounds": {
          "type": "object",
          "additionalProperties": false,
          "required": [
            "left",
            "top",
            "right",
            "bottom"
          ],
          "properties": {
            "left": {
              "type": "integer"
            },
            "top": {
              "type": "integer"
            },
            "right": {
              "type": "integer"
            },
            "bottom": {
              "type": "integer"
            }
          }
        },
        "actions": {
          "type": "array",
          "maxItems": 64,
          "items": {
            "type": "string"
          }
        },
        "clickable": {
          "type": "boolean"
        },
        "enabled": {
          "type": "boolean"
        },
        "focused": {
          "type": "boolean"
        },
        "focusable": {
          "type": "boolean"
        },
        "scrollable": {
          "type": "boolean"
        },
        "selected": {
          "type": "boolean"
        },
        "checked": {
          "type": "boolean"
        },
        "password": {
          "type": "boolean"
        },
        "sensitive": {
          "type": "boolean"
        },
        "childCount": {
          "type": "integer",
          "minimum": 0
        },
        "children": {
          "type": "array",
          "maxItems": 4096,
          "items": {
            "type": "object"
          }
        }
      }
    },
    "accessibilityNodeList": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "nodes",
        "truncated"
      ],
      "properties": {
        "nodes": {
          "type": "array",
          "maxItems": 4096,
          "items": {
            "$ref": "#/schemas/accessibilityNode"
          }
        },
        "truncated": {
          "type": "boolean"
        }
      }
    },
    "accessibilityNodeOrNull": {
      "$ref": "#/schemas/accessibilityNode",
      "type": [
        "object",
        "null"
      ]
    },
    "accessibilityEventType": {
      "type": "string",
      "enum": [
        "*",
        "viewClicked",
        "viewLongClicked",
        "viewSelected",
        "viewFocused",
        "viewTextChanged",
        "windowStateChanged",
        "notificationStateChanged",
        "viewHoverEnter",
        "viewHoverExit",
        "touchExplorationGestureStart",
        "touchExplorationGestureEnd",
        "windowContentChanged",
        "viewScrolled",
        "viewTextSelectionChanged",
        "announcement",
        "viewAccessibilityFocused",
        "viewAccessibilityFocusCleared",
        "viewTextTraversed",
        "gestureDetectionStart",
        "gestureDetectionEnd",
        "touchInteractionStart",
        "touchInteractionEnd",
        "windowsChanged",
        "viewContextClicked",
        "assistReadingContext"
      ]
    },
    "accessibilityNodeSelector": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "className": {
          "type": "string",
          "maxLength": 512
        },
        "packageName": {
          "type": "string",
          "maxLength": 256
        },
        "viewIdResourceName": {
          "type": "string",
          "maxLength": 512
        },
        "text": {
          "type": "string",
          "maxLength": 4096
        },
        "contentDescription": {
          "type": "string",
          "maxLength": 4096
        },
        "clickable": {
          "type": "boolean"
        },
        "enabled": {
          "type": "boolean"
        },
        "focused": {
          "type": "boolean"
        },
        "focusable": {
          "type": "boolean"
        },
        "scrollable": {
          "type": "boolean"
        },
        "selected": {
          "type": "boolean"
        },
        "checked": {
          "type": "boolean"
        },
        "password": {
          "type": "boolean"
        },
        "sensitive": {
          "type": "boolean"
        }
      }
    },
    "accessibilityGesturePoint": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "x",
        "y"
      ],
      "properties": {
        "x": {
          "type": "number",
          "minimum": 0
        },
        "y": {
          "type": "number",
          "minimum": 0
        }
      }
    },
    "accessibilityGestureStroke": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "points",
        "durationMs"
      ],
      "properties": {
        "points": {
          "type": "array",
          "minItems": 1,
          "maxItems": 128,
          "items": {
            "$ref": "#/schemas/accessibilityGesturePoint"
          }
        },
        "startTimeMs": {
          "type": "integer",
          "minimum": 0,
          "maximum": 60000
        },
        "durationMs": {
          "type": "integer",
          "minimum": 1,
          "maximum": 60000
        }
      }
    },
    "automationReceipt": {
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
          "type": "string"
        },
        "idempotencyKey": {
          "type": "string"
        },
        "completedAt": {
          "type": "string"
        },
        "result": {}
      }
    },
    "packageInfo": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "packageName",
        "label",
        "versionName",
        "versionCode",
        "uid",
        "enabled",
        "system",
        "firstInstallTime",
        "lastUpdateTime",
        "requestedPermissions",
        "grantedPermissions",
        "signingCertificates",
        "components"
      ],
      "properties": {
        "packageName": {
          "type": "string"
        },
        "label": {
          "type": "string"
        },
        "versionName": {
          "type": "string"
        },
        "versionCode": {
          "type": "integer",
          "minimum": 0
        },
        "uid": {
          "type": "integer",
          "minimum": 0
        },
        "enabled": {
          "type": "boolean"
        },
        "system": {
          "type": "boolean"
        },
        "firstInstallTime": {
          "type": "integer",
          "minimum": 0
        },
        "lastUpdateTime": {
          "type": "integer",
          "minimum": 0
        },
        "requestedPermissions": {
          "type": "array",
          "items": {
            "type": "string"
          }
        },
        "grantedPermissions": {
          "type": "array",
          "items": {
            "type": "string"
          }
        },
        "signingCertificates": {
          "type": "array",
          "items": {
            "type": "string"
          }
        },
        "components": {
          "type": "object",
          "additionalProperties": false,
          "required": [
            "activities",
            "services",
            "receivers",
            "providers"
          ],
          "properties": {
            "activities": {
              "type": "array",
              "items": {
                "type": "string"
              }
            },
            "services": {
              "type": "array",
              "items": {
                "type": "string"
              }
            },
            "receivers": {
              "type": "array",
              "items": {
                "type": "string"
              }
            },
            "providers": {
              "type": "array",
              "items": {
                "type": "string"
              }
            }
          }
        }
      }
    },
    "packageList": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "packages",
        "truncated"
      ],
      "properties": {
        "packages": {
          "type": "array",
          "maxItems": 4096,
          "items": {
            "$ref": "#/schemas/packageInfo"
          }
        },
        "truncated": {
          "type": "boolean"
        }
      }
    },
    "resolvedActivityList": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "activities"
      ],
      "properties": {
        "activities": {
          "type": "array",
          "maxItems": 256,
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": [
              "packageName",
              "className",
              "exported"
            ],
            "properties": {
              "packageName": {
                "type": "string"
              },
              "className": {
                "type": "string"
              },
              "exported": {
                "type": "boolean"
              }
            }
          }
        }
      }
    },
    "settingsOpenResult": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "opened",
        "action"
      ],
      "properties": {
        "opened": {
          "type": "boolean"
        },
        "action": {
          "type": "string"
        }
      }
    }
  },
  "capabilities": [
    {
      "id": "runtime.lifecycle@1",
      "stability": "stable",
      "runtimeFloor": 2,
      "permission": null,
      "confirmation": "none",
      "idempotency": "none",
      "quota": null,
      "timeoutMs": 5000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": [
          "DOCUMENT_START_SCRIPT",
          "WEB_MESSAGE_LISTENER"
        ]
      },
      "methods": [
        {
          "name": "handshake",
          "request": {
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
          },
          "response": {
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
          },
          "errors": [
            "capability_unavailable"
          ]
        },
        {
          "name": "ready",
          "request": {
            "$ref": "#/schemas/empty"
          },
          "response": {
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
          },
          "errors": []
        },
        {
          "name": "close",
          "request": {
            "$ref": "#/schemas/empty"
          },
          "response": {
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
          },
          "errors": []
        }
      ],
      "events": [
        {
          "name": "resume",
          "data": {
            "$ref": "#/schemas/empty"
          }
        },
        {
          "name": "pause",
          "data": {
            "$ref": "#/schemas/empty"
          }
        },
        {
          "name": "theme",
          "data": {
            "type": "object",
            "additionalProperties": false,
            "required": [
              "colorScheme",
              "reducedMotion",
              "highContrast"
            ],
            "properties": {
              "colorScheme": {
                "type": "string",
                "enum": [
                  "light",
                  "dark"
                ]
              },
              "reducedMotion": {
                "type": "boolean"
              },
              "highContrast": {
                "type": "boolean"
              }
            }
          }
        },
        {
          "name": "resize",
          "data": {
            "type": "object",
            "additionalProperties": false,
            "required": [
              "viewportWidthPx",
              "viewportHeightPx",
              "density",
              "fontScale",
              "orientation",
              "safeAreaTopPx",
              "safeAreaRightPx",
              "safeAreaBottomPx",
              "safeAreaLeftPx",
              "imeHeightPx"
            ],
            "properties": {
              "viewportWidthPx": {
                "type": "integer",
                "minimum": 0
              },
              "viewportHeightPx": {
                "type": "integer",
                "minimum": 0
              },
              "density": {
                "type": "number",
                "minimum": 0
              },
              "fontScale": {
                "type": "number",
                "minimum": 0
              },
              "orientation": {
                "type": "string",
                "enum": [
                  "portrait",
                  "landscape"
                ]
              },
              "safeAreaTopPx": {
                "type": "integer",
                "minimum": 0
              },
              "safeAreaRightPx": {
                "type": "integer",
                "minimum": 0
              },
              "safeAreaBottomPx": {
                "type": "integer",
                "minimum": 0
              },
              "safeAreaLeftPx": {
                "type": "integer",
                "minimum": 0
              },
              "imeHeightPx": {
                "type": "integer",
                "minimum": 0
              }
            }
          }
        },
        {
          "name": "network",
          "data": {
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
                "type": "string"
              }
            }
          }
        },
        {
          "name": "back",
          "requiresAcknowledgement": true,
          "data": {
            "$ref": "#/schemas/empty"
          }
        }
      ]
    },
    {
      "id": "configuration.read@1",
      "stability": "stable",
      "runtimeFloor": 2,
      "permission": {
        "id": "app.configuration.read",
        "title": "读取插件配置",
        "description": "读取用户为当前插件填写的已声明配置项。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": null,
      "timeoutMs": 5000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "get",
          "request": {
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
          },
          "response": {
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
          },
          "errors": [
            "permission_denied",
            "invalid_request"
          ]
        }
      ]
    },
    {
      "id": "remote.frame@1",
      "stability": "stable",
      "runtimeFloor": 2,
      "permission": {
        "id": "remote.frame",
        "title": "嵌入远程页面",
        "description": "允许在无原生桥的 sandbox iframe 中加载已声明来源。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": null,
      "timeoutMs": 0,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": []
    },
    {
      "id": "navigation.external@1",
      "stability": "stable",
      "runtimeFloor": 2,
      "permission": {
        "id": "navigation.external",
        "title": "打开外部链接",
        "description": "通过用户手势在系统浏览器打开已声明来源。"
      },
      "confirmation": "userGesture",
      "idempotency": "none",
      "quota": null,
      "timeoutMs": 5000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "open",
          "request": {
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
          },
          "response": {
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
          },
          "errors": [
            "origin_denied",
            "user_cancelled"
          ]
        }
      ]
    },
    {
      "id": "identity.profile@1",
      "stability": "stable",
      "runtimeFloor": 2,
      "permission": {
        "id": "identity.profile.read",
        "title": "读取个人身份信息",
        "description": "读取姓名、学号、学院、专业和邮箱等本地同步资料。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": null,
      "timeoutMs": 15000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "getProfile",
          "request": {
            "$ref": "#/schemas/readOptions"
          },
          "response": {
            "$ref": "#/schemas/studentProfileRead"
          },
          "errors": [
            "permission_denied",
            "network_timeout"
          ]
        }
      ]
    },
    {
      "id": "academic.timetable@1",
      "stability": "stable",
      "runtimeFloor": 2,
      "permission": {
        "id": "academic.timetable.read",
        "title": "读取课表",
        "description": "读取本地或校园系统中的课程表。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": null,
      "timeoutMs": 15000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "getTimetable",
          "request": {
            "$ref": "#/schemas/readOptions"
          },
          "response": {
            "$ref": "#/schemas/timetableRead"
          },
          "errors": [
            "permission_denied",
            "network_timeout"
          ]
        }
      ]
    },
    {
      "id": "academic.scores@1",
      "stability": "stable",
      "runtimeFloor": 2,
      "permission": {
        "id": "academic.scores.read",
        "title": "读取成绩",
        "description": "读取当前与历史成绩。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": null,
      "timeoutMs": 15000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "getScores",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/scoreRead"
          },
          "errors": [
            "permission_denied",
            "network_timeout"
          ]
        },
        {
          "name": "getHistoryScores",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/scoreRead"
          },
          "errors": [
            "permission_denied",
            "network_timeout"
          ]
        }
      ]
    },
    {
      "id": "academic.exams@1",
      "stability": "stable",
      "runtimeFloor": 2,
      "permission": {
        "id": "academic.exams.read",
        "title": "读取考试安排",
        "description": "读取考试时间与地点。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": null,
      "timeoutMs": 15000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "getExams",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/examRead"
          },
          "errors": [
            "permission_denied",
            "network_timeout"
          ]
        }
      ]
    },
    {
      "id": "academic.calendar@1",
      "stability": "stable",
      "runtimeFloor": 2,
      "permission": {
        "id": "academic.calendar.read",
        "title": "读取校历",
        "description": "读取校历与教学周信息。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": null,
      "timeoutMs": 15000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "getCalendar",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/calendarRead"
          },
          "errors": [
            "permission_denied",
            "network_timeout"
          ]
        }
      ]
    },
    {
      "id": "academic.progress@1",
      "stability": "stable",
      "runtimeFloor": 2,
      "permission": {
        "id": "academic.progress.read",
        "title": "读取学业进度",
        "description": "读取培养方案完成情况。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": null,
      "timeoutMs": 15000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "getProgress",
          "request": {
            "$ref": "#/schemas/readOptions"
          },
          "response": {
            "$ref": "#/schemas/academicProgressRead"
          },
          "errors": [
            "permission_denied",
            "network_timeout"
          ]
        }
      ]
    },
    {
      "id": "academic.homework@1",
      "stability": "stable",
      "runtimeFloor": 2,
      "permission": {
        "id": "academic.homework.read",
        "title": "读取作业",
        "description": "读取作业列表与状态。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": null,
      "timeoutMs": 15000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "getHomework",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/homeworkRead"
          },
          "errors": [
            "permission_denied",
            "network_timeout"
          ]
        }
      ]
    },
    {
      "id": "academic.resources@1",
      "stability": "stable",
      "runtimeFloor": 2,
      "permission": {
        "id": "academic.course_resources.read",
        "title": "读取课程资源",
        "description": "读取课程资料目录与资源元数据。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": null,
      "timeoutMs": 15000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "getCourseResources",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/courseResourcesRead"
          },
          "errors": [
            "permission_denied",
            "network_timeout"
          ]
        }
      ]
    },
    {
      "id": "mail.read@1",
      "stability": "stable",
      "runtimeFloor": 2,
      "permission": {
        "id": "mail.read",
        "title": "读取校园邮件",
        "description": "读取邮件文件夹、列表与正文。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": null,
      "timeoutMs": 15000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "listFolders",
          "request": {
            "$ref": "#/schemas/readOptions"
          },
          "response": {
            "$ref": "#/schemas/mailFoldersRead"
          },
          "errors": [
            "permission_denied",
            "network_timeout"
          ]
        },
        {
          "name": "listMessages",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/mailMessagesRead"
          },
          "errors": [
            "permission_denied",
            "network_timeout"
          ]
        },
        {
          "name": "getMessage",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/mailMessageRead"
          },
          "errors": [
            "permission_denied",
            "network_timeout"
          ]
        }
      ]
    },
    {
      "id": "campus.request@1",
      "stability": "stable",
      "runtimeFloor": 2,
      "permission": {
        "id": "campus.request",
        "title": "访问只读校园代理",
        "description": "调用宿主登记的 MIS、AA 或 VE 只读路径，不暴露会话信息。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": {
        "responseBytes": 5242880
      },
      "timeoutMs": 15000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "request",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/campusRead"
          },
          "errors": [
            "permission_denied",
            "invalid_request",
            "http_error",
            "resource_too_large"
          ]
        }
      ]
    },
    {
      "id": "network.request@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "network.request",
        "title": "通过宿主访问公网",
        "description": "使用不含 Cookie 和宿主认证信息的隔离网络客户端访问已声明来源。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": {
        "pluginConcurrency": 4,
        "originConcurrency": 2,
        "inlineResponseBytes": 1048576,
        "redirects": 5
      },
      "timeoutMs": 15000,
      "maxTimeoutMs": 60000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "request",
          "request": {
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
          },
          "response": {
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
          },
          "errors": [
            "origin_denied",
            "network_timeout",
            "http_error",
            "quota_exceeded",
            "resource_too_large",
            "user_cancelled"
          ]
        }
      ],
      "events": [
        {
          "name": "progress",
          "data": {
            "type": "object",
            "additionalProperties": false,
            "required": [
              "loaded",
              "phase"
            ],
            "properties": {
              "loaded": {
                "type": "integer",
                "minimum": 0
              },
              "total": {
                "type": "integer",
                "minimum": 0
              },
              "phase": {
                "type": "string",
                "enum": [
                  "upload",
                  "response"
                ]
              }
            }
          }
        }
      ]
    },
    {
      "id": "storage.kv@2",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "storage.kv",
        "title": "保存插件数据",
        "description": "在当前发布者与插件隔离的加密空间中保存 JSON 数据。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": {
        "pluginBytes": 10485760,
        "itemBytes": 262144,
        "keys": 1024
      },
      "timeoutMs": 10000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "get",
          "request": {
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
          },
          "response": {
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
          },
          "errors": [
            "invalid_request"
          ]
        },
        {
          "name": "set",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/kvTransaction"
          },
          "errors": [
            "invalid_request",
            "quota_exceeded",
            "resource_too_large",
            "idempotency_conflict"
          ]
        },
        {
          "name": "remove",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/kvRemove"
          },
          "errors": [
            "invalid_request",
            "idempotency_conflict"
          ]
        },
        {
          "name": "keys",
          "request": {
            "$ref": "#/schemas/empty"
          },
          "response": {
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
          },
          "errors": []
        },
        {
          "name": "usage",
          "request": {
            "$ref": "#/schemas/empty"
          },
          "response": {
            "$ref": "#/schemas/kvUsage"
          },
          "errors": []
        },
        {
          "name": "batch",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/kvTransaction"
          },
          "errors": [
            "invalid_request",
            "quota_exceeded",
            "resource_too_large",
            "idempotency_conflict"
          ]
        },
        {
          "name": "transaction",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/kvTransaction"
          },
          "errors": [
            "invalid_request",
            "quota_exceeded",
            "resource_too_large",
            "idempotency_conflict"
          ]
        },
        {
          "name": "export",
          "request": {
            "$ref": "#/schemas/empty"
          },
          "response": {
            "$ref": "#/schemas/resourceHandle"
          },
          "errors": [
            "resource_too_large"
          ]
        },
        {
          "name": "import",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/kvTransaction"
          },
          "errors": [
            "invalid_request",
            "quota_exceeded",
            "resource_too_large",
            "migration_failed",
            "idempotency_conflict"
          ]
        }
      ],
      "events": [
        {
          "name": "changed",
          "data": {
            "type": "object",
            "additionalProperties": false,
            "required": [
              "revision",
              "keys",
              "cleared"
            ],
            "properties": {
              "revision": {
                "type": "integer",
                "minimum": 0
              },
              "keys": {
                "type": "array",
                "items": {
                  "type": "string"
                }
              },
              "cleared": {
                "type": "boolean"
              }
            }
          }
        }
      ]
    },
    {
      "id": "storage.blob@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "storage.blob",
        "title": "保存大文件",
        "description": "在隔离的加密 Blob 空间保存不可变内容寻址数据。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": {
        "pluginBytes": 268435456,
        "itemBytes": 67108864
      },
      "timeoutMs": 60000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "put",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/resourceHandle"
          },
          "errors": [
            "invalid_request",
            "quota_exceeded",
            "resource_too_large",
            "user_cancelled"
          ]
        },
        {
          "name": "getInfo",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/resourceHandle"
          },
          "errors": [
            "invalid_request"
          ]
        },
        {
          "name": "delete",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/deletionResult"
          },
          "errors": [
            "invalid_request"
          ]
        }
      ]
    },
    {
      "id": "cache.resource@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "cache.resource",
        "title": "缓存网络资源",
        "description": "在可淘汰的隔离 LRU 缓存中保存资源。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": {
        "pluginBytes": 536870912,
        "globalBytes": 1073741824,
        "itemBytes": 262144000
      },
      "timeoutMs": 60000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "put",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/resourceHandle"
          },
          "errors": [
            "invalid_request",
            "quota_exceeded",
            "resource_too_large",
            "user_cancelled"
          ]
        },
        {
          "name": "promote",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/resourceHandle"
          },
          "errors": [
            "invalid_request",
            "quota_exceeded"
          ]
        },
        {
          "name": "deleteHandle",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/deletionResult"
          },
          "errors": [
            "invalid_request"
          ]
        },
        {
          "name": "match",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/resourceHandleOrNull"
          },
          "errors": [
            "invalid_request"
          ]
        },
        {
          "name": "delete",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/deletionResult"
          },
          "errors": [
            "invalid_request"
          ]
        },
        {
          "name": "pin",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/pinResult"
          },
          "errors": [
            "invalid_request"
          ]
        },
        {
          "name": "usage",
          "request": {
            "$ref": "#/schemas/empty"
          },
          "response": {
            "$ref": "#/schemas/cacheUsage"
          },
          "errors": []
        }
      ]
    },
    {
      "id": "android.accessibility.events@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "android.accessibility.events",
        "title": "读取无障碍事件",
        "description": "读取用户启用的 Android 无障碍事件；持久订阅会在插件后台运行时继续生效。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": {
        "subscriptionsPerPlugin": 16,
        "eventsPerSecond": 60
      },
      "timeoutMs": 5000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "getStatus",
          "request": {
            "$ref": "#/schemas/empty"
          },
          "response": {
            "$ref": "#/schemas/accessibilityStatus"
          },
          "errors": [
            "capability_unavailable"
          ]
        },
        {
          "name": "subscribe",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/accessibilitySubscription"
          },
          "errors": [
            "capability_unavailable",
            "quota_exceeded"
          ]
        },
        {
          "name": "unsubscribe",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/deletionResult"
          },
          "errors": [
            "invalid_request"
          ]
        },
        {
          "name": "listSubscriptions",
          "request": {
            "$ref": "#/schemas/empty"
          },
          "response": {
            "type": "array",
            "items": {
              "$ref": "#/schemas/accessibilitySubscription"
            }
          },
          "errors": []
        }
      ],
      "events": [
        {
          "name": "received",
          "data": {
            "type": "object",
            "additionalProperties": false,
            "required": [
              "subscriptionId",
              "eventType",
              "packageName",
              "className",
              "eventTime",
              "source"
            ],
            "properties": {
              "subscriptionId": {
                "type": "string"
              },
              "eventType": {
                "type": "string"
              },
              "packageName": {
                "type": "string"
              },
              "className": {
                "type": "string"
              },
              "eventTime": {
                "type": "integer",
                "minimum": 0
              },
              "text": {
                "type": "array",
                "maxItems": 128,
                "items": {
                  "type": "string",
                  "maxLength": 4096
                }
              },
              "contentDescription": {
                "type": "string",
                "maxLength": 4096
              },
              "source": {
                "$ref": "#/schemas/accessibilityNodeOrNull"
              }
            }
          }
        }
      ]
    },
    {
      "id": "android.accessibility.nodes@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "android.accessibility.nodes",
        "title": "读取无障碍节点",
        "description": "读取活动窗口的结构化节点快照；密码和敏感输入值始终脱敏。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": {
        "maxNodes": 4096,
        "maxDepth": 64,
        "nodeTtlMs": 30000
      },
      "timeoutMs": 5000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "getRoot",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/accessibilityNode"
          },
          "errors": [
            "capability_unavailable",
            "resource_too_large"
          ]
        },
        {
          "name": "find",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/accessibilityNodeList"
          },
          "errors": [
            "capability_unavailable",
            "resource_too_large"
          ]
        },
        {
          "name": "get",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/accessibilityNode"
          },
          "errors": [
            "capability_unavailable",
            "invalid_request",
            "quota_exceeded"
          ]
        }
      ]
    },
    {
      "id": "android.accessibility.actions@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "android.accessibility.actions",
        "title": "执行无障碍动作",
        "description": "允许插件操作其他应用的无障碍节点、全局导航和触摸手势；首次授权后持续有效。"
      },
      "confirmation": "none",
      "idempotency": "required",
      "quota": {
        "actionsPerMinute": 120,
        "receiptRetentionDays": 7,
        "receiptsPerPlugin": 1024
      },
      "timeoutMs": 10000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "performNode",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/automationReceipt"
          },
          "errors": [
            "capability_unavailable",
            "invalid_request",
            "quota_exceeded",
            "idempotency_conflict"
          ]
        },
        {
          "name": "performGlobal",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/automationReceipt"
          },
          "errors": [
            "capability_unavailable",
            "invalid_request",
            "quota_exceeded",
            "idempotency_conflict"
          ]
        },
        {
          "name": "dispatchGesture",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/automationReceipt"
          },
          "errors": [
            "capability_unavailable",
            "invalid_request",
            "quota_exceeded",
            "idempotency_conflict"
          ]
        }
      ]
    },
    {
      "id": "android.packages.read@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "android.packages.read",
        "title": "读取已安装应用",
        "description": "读取设备上的应用包、权限、组件和签名摘要；不读取应用私有数据。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": {
        "maxPackages": 4096,
        "maxComponents": 4096
      },
      "timeoutMs": 10000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "list",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/packageList"
          },
          "errors": [
            "capability_unavailable",
            "resource_too_large"
          ]
        },
        {
          "name": "get",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/packageInfo"
          },
          "errors": [
            "capability_unavailable",
            "invalid_request"
          ]
        },
        {
          "name": "resolveIntent",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/resolvedActivityList"
          },
          "errors": [
            "capability_unavailable",
            "invalid_request"
          ]
        }
      ]
    },
    {
      "id": "android.settings.open@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "android.settings.open",
        "title": "打开系统设置",
        "description": "打开 android.settings.* 系统设置页面；宿主拒绝任意 Intent、组件和额外参数。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": {
        "opensPerMinute": 30
      },
      "timeoutMs": 5000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "open",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/settingsOpenResult"
          },
          "errors": [
            "capability_unavailable",
            "invalid_request",
            "quota_exceeded"
          ]
        }
      ]
    },
    {
      "id": "android.device.info@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "android.device.info",
        "description": "读取最小化的设备和宿主应用信息；不包含稳定硬件标识符。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": {
        "requestsPerMinute": 60
      },
      "timeoutMs": 5000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": [
          "DOCUMENT_START_SCRIPT",
          "WEB_MESSAGE_LISTENER"
        ]
      },
      "methods": [
        {
          "name": "getInfo",
          "request": {
            "$ref": "#/schemas/empty"
          },
          "response": {
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
          },
          "errors": [
            "capability_unavailable"
          ]
        }
      ],
      "events": []
    },
    {
      "id": "android.network.status@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "android.network.status",
        "description": "读取和订阅最小化网络状态；不返回 SSID、BSSID、MAC 或 IP。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": {
        "subscriptionsPerPlugin": 16,
        "eventsPerSecond": 60
      },
      "timeoutMs": 5000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": [
          "DOCUMENT_START_SCRIPT",
          "WEB_MESSAGE_LISTENER"
        ]
      },
      "methods": [
        {
          "name": "getStatus",
          "request": {
            "$ref": "#/schemas/empty"
          },
          "response": {
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
          },
          "errors": [
            "capability_unavailable"
          ]
        },
        {
          "name": "subscribe",
          "request": {
            "type": "object",
            "additionalProperties": false,
            "properties": {
              "persistent": {
                "type": "boolean",
                "default": false
              }
            }
          },
          "response": {
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
          },
          "errors": [
            "quota_exceeded",
            "capability_unavailable"
          ]
        },
        {
          "name": "unsubscribe",
          "request": {
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
          },
          "response": {
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
          },
          "errors": []
        }
      ],
      "events": [
        {
          "name": "changed",
          "data": {
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
        }
      ]
    },
    {
      "id": "android.battery.status@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "android.battery.status",
        "description": "读取和订阅电池状态。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": {
        "subscriptionsPerPlugin": 16,
        "eventsPerSecond": 60
      },
      "timeoutMs": 5000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": [
          "DOCUMENT_START_SCRIPT",
          "WEB_MESSAGE_LISTENER"
        ]
      },
      "methods": [
        {
          "name": "getStatus",
          "request": {
            "$ref": "#/schemas/empty"
          },
          "response": {
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
          },
          "errors": [
            "capability_unavailable"
          ]
        },
        {
          "name": "subscribe",
          "request": {
            "type": "object",
            "additionalProperties": false,
            "properties": {
              "persistent": {
                "type": "boolean",
                "default": false
              }
            }
          },
          "response": {
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
          },
          "errors": [
            "quota_exceeded",
            "capability_unavailable"
          ]
        },
        {
          "name": "unsubscribe",
          "request": {
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
          },
          "response": {
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
          },
          "errors": []
        }
      ],
      "events": [
        {
          "name": "changed",
          "data": {
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
        }
      ]
    },
    {
      "id": "android.haptics.perform@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "android.haptics.perform",
        "description": "执行受限时长的设备振动反馈。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": {
        "requestsPerMinute": 60
      },
      "timeoutMs": 5000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": [
          "DOCUMENT_START_SCRIPT",
          "WEB_MESSAGE_LISTENER"
        ]
      },
      "methods": [
        {
          "name": "perform",
          "request": {
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
          },
          "response": {
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
          },
          "errors": [
            "capability_unavailable",
            "quota_exceeded"
          ]
        }
      ],
      "events": []
    },
    {
      "id": "android.files.pick@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "android.files.pick",
        "description": "通过系统文件选择器导入受限大小的文件到插件加密 blob 存储。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": {
        "itemsPerRequest": 16,
        "bytesPerItem": 67108864
      },
      "timeoutMs": 120000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": [
          "DOCUMENT_START_SCRIPT",
          "WEB_MESSAGE_LISTENER"
        ]
      },
      "methods": [
        {
          "name": "pick",
          "request": {
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
          },
          "response": {
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
          },
          "errors": [
            "foreground_required",
            "user_cancelled",
            "quota_exceeded",
            "resource_too_large",
            "capability_unavailable"
          ]
        }
      ],
      "events": []
    },
    {
      "id": "android.files.save@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "android.files.save",
        "description": "通过系统保存面板将当前插件 blob 导出为用户选择的文件。"
      },
      "confirmation": "none",
      "idempotency": "required",
      "quota": {
        "bytesPerItem": 67108864
      },
      "timeoutMs": 120000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": [
          "DOCUMENT_START_SCRIPT",
          "WEB_MESSAGE_LISTENER"
        ]
      },
      "methods": [
        {
          "name": "save",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/commandReceipt"
          },
          "errors": [
            "foreground_required",
            "user_cancelled",
            "invalid_request",
            "resource_too_large",
            "idempotency_conflict",
            "capability_unavailable"
          ]
        }
      ],
      "events": []
    },
    {
      "id": "android.media.pick@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "android.media.pick",
        "description": "通过系统照片选择器导入图片或视频到插件加密 blob 存储。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": {
        "itemsPerRequest": 16,
        "bytesPerItem": 67108864
      },
      "timeoutMs": 120000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": [
          "DOCUMENT_START_SCRIPT",
          "WEB_MESSAGE_LISTENER"
        ]
      },
      "methods": [
        {
          "name": "pick",
          "request": {
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
          },
          "response": {
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
          },
          "errors": [
            "foreground_required",
            "user_cancelled",
            "quota_exceeded",
            "resource_too_large",
            "capability_unavailable"
          ]
        }
      ],
      "events": []
    },
    {
      "id": "android.share.open@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "android.share.open",
        "description": "通过 Android chooser 分享文本、HTTPS URL 或当前插件 blob。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": {
        "requestsPerMinute": 30
      },
      "timeoutMs": 30000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": [
          "DOCUMENT_START_SCRIPT",
          "WEB_MESSAGE_LISTENER"
        ]
      },
      "methods": [
        {
          "name": "open",
          "request": {
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
          },
          "response": {
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
          },
          "errors": [
            "foreground_required",
            "invalid_request",
            "quota_exceeded",
            "capability_unavailable"
          ]
        }
      ],
      "events": []
    },
    {
      "id": "android.notifications.post@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "android.notifications.post",
        "description": "显示、计划和取消受配额约束的插件本地通知。"
      },
      "confirmation": "none",
      "idempotency": "required",
      "quota": {
        "requestsPerHour": 60
      },
      "timeoutMs": 10000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": [
          "DOCUMENT_START_SCRIPT",
          "WEB_MESSAGE_LISTENER"
        ]
      },
      "methods": [
        {
          "name": "getStatus",
          "request": {
            "$ref": "#/schemas/empty"
          },
          "response": {
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
          },
          "errors": [
            "capability_unavailable"
          ]
        },
        {
          "name": "show",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/commandReceipt"
          },
          "errors": [
            "foreground_required",
            "permission_denied",
            "quota_exceeded",
            "idempotency_conflict",
            "capability_unavailable"
          ]
        },
        {
          "name": "schedule",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/commandReceipt"
          },
          "errors": [
            "foreground_required",
            "permission_denied",
            "quota_exceeded",
            "idempotency_conflict",
            "capability_unavailable"
          ]
        },
        {
          "name": "cancel",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/commandReceipt"
          },
          "errors": [
            "idempotency_conflict",
            "capability_unavailable"
          ]
        }
      ],
      "events": []
    },
    {
      "id": "android.location.read@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "android.location.read",
        "description": "读取一次前台位置；不会请求后台定位。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": {
        "requestsPerMinute": 12
      },
      "timeoutMs": 60000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": [
          "DOCUMENT_START_SCRIPT",
          "WEB_MESSAGE_LISTENER"
        ]
      },
      "methods": [
        {
          "name": "getStatus",
          "request": {
            "$ref": "#/schemas/empty"
          },
          "response": {
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
          },
          "errors": [
            "capability_unavailable"
          ]
        },
        {
          "name": "getCurrent",
          "request": {
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
          },
          "response": {
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
          },
          "errors": [
            "foreground_required",
            "permission_denied",
            "request_timeout",
            "capability_unavailable",
            "quota_exceeded"
          ]
        }
      ],
      "events": []
    },
    {
      "id": "android.calendar.read@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "android.calendar.read",
        "description": "读取受日期和条数限制的系统日历事件。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": {
        "requestsPerMinute": 30,
        "maxEvents": 200
      },
      "timeoutMs": 10000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": [
          "DOCUMENT_START_SCRIPT",
          "WEB_MESSAGE_LISTENER"
        ]
      },
      "methods": [
        {
          "name": "list",
          "request": {
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
          },
          "response": {
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
          },
          "errors": [
            "foreground_required",
            "permission_denied",
            "invalid_request",
            "quota_exceeded",
            "capability_unavailable"
          ]
        }
      ],
      "events": []
    },
    {
      "id": "android.calendar.write@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "android.calendar.write",
        "description": "创建、更新或删除日历事件；使用幂等回执。"
      },
      "confirmation": "none",
      "idempotency": "required",
      "quota": {
        "requestsPerMinute": 30
      },
      "timeoutMs": 10000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": [
          "DOCUMENT_START_SCRIPT",
          "WEB_MESSAGE_LISTENER"
        ]
      },
      "methods": [
        {
          "name": "create",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/commandReceipt"
          },
          "errors": [
            "foreground_required",
            "permission_denied",
            "invalid_request",
            "idempotency_conflict",
            "capability_unavailable"
          ]
        },
        {
          "name": "update",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/commandReceipt"
          },
          "errors": [
            "foreground_required",
            "permission_denied",
            "invalid_request",
            "idempotency_conflict",
            "capability_unavailable"
          ]
        },
        {
          "name": "delete",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/commandReceipt"
          },
          "errors": [
            "foreground_required",
            "permission_denied",
            "invalid_request",
            "idempotency_conflict",
            "capability_unavailable"
          ]
        }
      ],
      "events": []
    },
    {
      "id": "android.camera.capture@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "android.camera.capture",
        "description": "通过系统相机 UI 拍摄照片并导入加密 blob 存储。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": {
        "bytesPerItem": 67108864
      },
      "timeoutMs": 120000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": [
          "DOCUMENT_START_SCRIPT",
          "WEB_MESSAGE_LISTENER"
        ]
      },
      "methods": [
        {
          "name": "capturePhoto",
          "request": {
            "$ref": "#/schemas/empty"
          },
          "response": {
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
          },
          "errors": [
            "foreground_required",
            "user_cancelled",
            "resource_too_large",
            "capability_unavailable"
          ]
        }
      ],
      "events": []
    },
    {
      "id": "android.audio.record@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "android.audio.record",
        "description": "在可见前台插件 runtime 中录制受时长限制的音频。"
      },
      "confirmation": "none",
      "idempotency": "required",
      "quota": {
        "maxDurationMs": 600000,
        "bytesPerItem": 67108864
      },
      "timeoutMs": 30000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": [
          "DOCUMENT_START_SCRIPT",
          "WEB_MESSAGE_LISTENER"
        ]
      },
      "methods": [
        {
          "name": "start",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/commandReceipt"
          },
          "errors": [
            "foreground_required",
            "permission_denied",
            "invalid_request",
            "idempotency_conflict",
            "capability_unavailable"
          ]
        },
        {
          "name": "stop",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/commandReceipt"
          },
          "errors": [
            "foreground_required",
            "invalid_request",
            "resource_too_large",
            "idempotency_conflict",
            "capability_unavailable"
          ]
        }
      ],
      "events": []
    },
    {
      "id": "android.sensors.read@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "android.sensors.read",
        "description": "读取受限频率的常规设备传感器，不包含 body sensor 数据。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": {
        "subscriptionsPerPlugin": 16,
        "eventsPerSecond": 20
      },
      "timeoutMs": 5000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": [
          "DOCUMENT_START_SCRIPT",
          "WEB_MESSAGE_LISTENER"
        ]
      },
      "methods": [
        {
          "name": "list",
          "request": {
            "$ref": "#/schemas/empty"
          },
          "response": {
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
          },
          "errors": [
            "capability_unavailable"
          ]
        },
        {
          "name": "subscribe",
          "request": {
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
          },
          "response": {
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
          },
          "errors": [
            "quota_exceeded",
            "capability_unavailable"
          ]
        },
        {
          "name": "unsubscribe",
          "request": {
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
          },
          "response": {
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
          },
          "errors": []
        }
      ],
      "events": [
        {
          "name": "changed",
          "data": {
            "type": "object",
            "additionalProperties": false,
            "required": [
              "subscriptionId",
              "sensor",
              "values",
              "timestampMs"
            ],
            "properties": {
              "subscriptionId": {
                "type": "string"
              },
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
              "values": {
                "type": "array",
                "maxItems": 3,
                "items": {
                  "type": "number"
                }
              },
              "timestampMs": {
                "type": "integer",
                "minimum": 0
              }
            }
          }
        }
      ]
    },
    {
      "id": "android.biometric.verify@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "android.biometric.verify",
        "description": "打开系统生物识别确认，只返回结果且不读取生物特征数据。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": {
        "requestsPerMinute": 12
      },
      "timeoutMs": 60000,
      "support": {
        "androidMinApi": 28,
        "webViewFeatures": [
          "DOCUMENT_START_SCRIPT",
          "WEB_MESSAGE_LISTENER"
        ]
      },
      "methods": [
        {
          "name": "getStatus",
          "request": {
            "$ref": "#/schemas/empty"
          },
          "response": {
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
          },
          "errors": [
            "capability_unavailable"
          ]
        },
        {
          "name": "verify",
          "request": {
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
          },
          "response": {
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
          },
          "errors": [
            "foreground_required",
            "user_cancelled",
            "capability_unavailable",
            "quota_exceeded"
          ]
        }
      ],
      "events": []
    },
    {
      "id": "academic.userCourses.command@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "academic.user_courses.write",
        "title": "修改自定义课程",
        "description": "新增、修改或删除用户自定义课程。"
      },
      "confirmation": "eachCall",
      "idempotency": "required",
      "quota": {
        "receiptRetentionDays": 7,
        "receiptsPerPlugin": 1024
      },
      "timeoutMs": 15000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "save",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/commandReceipt"
          },
          "errors": [
            "permission_denied",
            "user_cancelled",
            "idempotency_conflict"
          ]
        },
        {
          "name": "delete",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/commandReceipt"
          },
          "errors": [
            "permission_denied",
            "user_cancelled",
            "idempotency_conflict"
          ]
        }
      ]
    },
    {
      "id": "academic.homework.submit@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "academic.homework.submit",
        "title": "提交作业",
        "description": "向课程平台提交作业；每次调用都需要用户确认。"
      },
      "confirmation": "eachCall",
      "idempotency": "required",
      "quota": {
        "receiptRetentionDays": 7,
        "receiptsPerPlugin": 1024
      },
      "timeoutMs": 60000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "submit",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/commandReceipt"
          },
          "errors": [
            "permission_denied",
            "user_cancelled",
            "idempotency_conflict",
            "http_error"
          ]
        }
      ]
    },
    {
      "id": "mail.send@1",
      "stability": "beta",
      "runtimeFloor": 2,
      "permission": {
        "id": "mail.send",
        "title": "发送校园邮件",
        "description": "发送校园邮件；每次调用都需要用户确认。"
      },
      "confirmation": "eachCall",
      "idempotency": "required",
      "quota": {
        "receiptRetentionDays": 7,
        "receiptsPerPlugin": 1024
      },
      "timeoutMs": 60000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "send",
          "request": {
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
          },
          "response": {
            "$ref": "#/schemas/commandReceipt"
          },
          "errors": [
            "permission_denied",
            "user_cancelled",
            "idempotency_conflict",
            "http_error"
          ]
        }
      ]
    },
    {
      "id": "android.app.autoStart@1",
      "stability": "beta",
      "runtimeFloor": 4,
      "optionalOnly": true,
      "permission": {
        "id": "android.app.autoStart",
        "title": "打开主应用时自动启动插件",
        "description": "允许插件在你打开 BJTU MIS 主应用且主 Activity 可见时加载并在后台运行。默认关闭，不会开机启动或绕过系统权限。"
      },
      "confirmation": "none",
      "idempotency": "none",
      "quota": {
        "maxBackgroundRuntimes": 4
      },
      "timeoutMs": 0,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": []
    },
    {
      "id": "android.session.keepAlive@1",
      "stability": "beta",
      "runtimeFloor": 3,
      "permission": {
        "id": "android.session.keepAlive",
        "title": "维持 MIS 会话",
        "description": "允许插件在限时任务期间维持 BJTU MIS 会话，并显示持续通知。"
      },
      "confirmation": "none",
      "idempotency": "required",
      "quota": {
        "maxLeasesPerPlugin": 2,
        "maxDurationMs": 3600000,
        "requestsPerMinute": 6,
        "durationPerHourMs": 3600000
      },
      "timeoutMs": 10000,
      "support": {
        "androidMinApi": 26,
        "webViewFeatures": []
      },
      "methods": [
        {
          "name": "acquire",
          "request": {
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
          },
          "response": {
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
          },
          "errors": [
            "permission_denied",
            "capability_unavailable",
            "foreground_required",
            "invalid_request",
            "quota_exceeded",
            "idempotency_conflict",
            "lease_not_found",
            "foreground_service_denied",
            "notifications_unavailable"
          ]
        },
        {
          "name": "renew",
          "request": {
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
          },
          "response": {
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
          },
          "errors": [
            "permission_denied",
            "capability_unavailable",
            "foreground_required",
            "invalid_request",
            "quota_exceeded",
            "idempotency_conflict",
            "lease_not_found",
            "foreground_service_denied",
            "notifications_unavailable"
          ]
        },
        {
          "name": "release",
          "request": {
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
          },
          "response": {
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
          },
          "errors": [
            "permission_denied",
            "capability_unavailable",
            "foreground_required",
            "invalid_request",
            "quota_exceeded",
            "idempotency_conflict",
            "lease_not_found",
            "foreground_service_denied",
            "notifications_unavailable"
          ]
        },
        {
          "name": "getStatus",
          "request": {
            "type": "object",
            "additionalProperties": false
          },
          "response": {
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
          },
          "errors": [
            "permission_denied",
            "capability_unavailable",
            "foreground_required",
            "invalid_request",
            "quota_exceeded",
            "idempotency_conflict",
            "lease_not_found",
            "foreground_service_denied",
            "notifications_unavailable"
          ]
        }
      ],
      "events": [
        {
          "name": "ended",
          "data": {
            "type": "object",
            "additionalProperties": false,
            "required": [
              "leaseId",
              "reason"
            ],
            "properties": {
              "leaseId": {
                "type": "string",
                "minLength": 1,
                "maxLength": 160
              },
              "reason": {
                "type": "string",
                "enum": [
                  "released",
                  "expired",
                  "revoked",
                  "clock_changed",
                  "user_stopped",
                  "max_runtime",
                  "session_unavailable",
                  "service_unavailable"
                ]
              }
            }
          }
        }
      ]
    }
  ]
} as const;
