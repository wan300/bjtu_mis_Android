import {
  CAPABILITY_IDS,
  CAPABILITY_REGISTRY,
  PROTOCOL_VERSION,
  type CapabilityId,
  type CapabilityEventData,
  type CapabilityEventRoute,
  type CapabilityMethodMap,
  type CapabilityRequest,
  type CapabilityResponse,
  type CapabilityRoute,
  type PluginErrorCode
} from './generated/contracts.js';
import {
  WebViewBridgeTransport,
  WebViewTransportError
} from './internal/webview-transport.js';

export * from './generated/contracts.js';

export const SDK_VERSION = '0.2.0';
const PRIVATE_MIGRATION_BRIDGE_KEY = '__BJTU_PLUGIN_MIGRATION_V2__';

export type BinaryTransport = 'arraybuffer' | 'base64url-chunks-v1';

export interface PluginRequestV2 {
  protocolVersion: typeof PROTOCOL_VERSION;
  requestId: string;
  capability: CapabilityId;
  method: string;
  params: unknown;
  binary?: {
    transport: BinaryTransport;
    size: number;
    chunks: number;
    sha256: string;
  };
}

export interface PluginSuccessV2 {
  protocolVersion: typeof PROTOCOL_VERSION;
  requestId: string;
  ok: true;
  result: unknown;
}

export interface PluginFailureV2 {
  protocolVersion: typeof PROTOCOL_VERSION;
  requestId: string;
  ok: false;
  error: {
    code: PluginErrorCode;
    message: string;
    retryable?: boolean;
    details?: unknown;
  };
}

export type PluginResponseV2 = PluginSuccessV2 | PluginFailureV2;

export interface PluginEventV2 {
  protocolVersion: typeof PROTOCOL_VERSION;
  eventId: string;
  capability: CapabilityId;
  event: string;
  requestId?: string;
  data?: unknown;
  requiresAcknowledgement?: boolean;
}

export interface InvokeOptions {
  signal?: AbortSignal;
  onProgress?: (progress: PluginProgress) => void;
  timeoutMs?: number;
}

export interface PluginProgress {
  loaded: number;
  total?: number;
  phase?: string;
}

interface PluginTransport {
  send(request: PluginRequestV2, binary?: ArrayBuffer): Promise<PluginResponseV2>;
  cancel(requestId: string): void;
  subscribe(listener: PluginEventListener): () => void;
  configureBinaryTransport?(transport: BinaryTransport | undefined): void;
}

type PluginEventListener = (
  event: PluginEventV2
) => boolean | void | Promise<boolean | void>;

interface PluginMigrationBridge {
  invoke(
    capability: 'storage.kv@2' | 'runtime.migration@1',
    method: string,
    params?: Record<string, unknown>
  ): Promise<PluginResponseV2>;
}

export class BjtuPluginError extends Error {
  readonly code: PluginErrorCode;
  readonly retryable: boolean;
  readonly details: unknown;

  constructor(
    code: PluginErrorCode,
    message: string,
    options: { retryable?: boolean; details?: unknown; cause?: unknown } = {}
  ) {
    super(message, { cause: options.cause });
    this.name = 'BjtuPluginError';
    this.code = code;
    this.retryable = options.retryable ?? false;
    this.details = options.details;
  }
}

export interface CampusReadMeta {
  syncedAt: string;
  source: 'cache' | 'network' | 'mixed';
  coverage: 'complete' | 'partial' | 'unknown';
  fromCache: boolean;
}

export interface CampusReadResult<T = unknown> {
  data: T;
  meta: CampusReadMeta;
}

export interface NetworkRequest {
  url: string;
  method?: 'GET' | 'HEAD' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  headers?: Record<string, string>;
  body?: unknown;
  bodyType?: 'json' | 'text' | 'formData' | 'formUrlEncoded' | 'blob';
  timeoutMs?: number;
}

export type ResourceHandle = CapabilityResponse<'storage.blob@1#getInfo'>;
export type NetworkResponse = CapabilityResponse<'network.request@1#request'>;
export type KvGetResult = CapabilityResponse<'storage.kv@2#get'>;
export type KvSetResult = CapabilityResponse<'storage.kv@2#set'>;
export type KvRemoveResult = CapabilityResponse<'storage.kv@2#remove'>;
export type KvKeysResult = CapabilityResponse<'storage.kv@2#keys'>;
export type KvUsageResult = CapabilityResponse<'storage.kv@2#usage'>;
export type KvBatchResult = CapabilityResponse<'storage.kv@2#batch'>;
export type KvTransactionResult = CapabilityResponse<'storage.kv@2#transaction'>;
export type KvImportResult = CapabilityResponse<'storage.kv@2#import'>;
export type KvChangedEvent = CapabilityEventData<'storage.kv@2#changed'>;
export type StudentProfileReadResult = CapabilityResponse<'identity.profile@1#getProfile'>;
export type TimetableReadResult = CapabilityResponse<'academic.timetable@1#getTimetable'>;
export type ScoresReadResult = CapabilityResponse<'academic.scores@1#getScores'>;
export type ExamsReadResult = CapabilityResponse<'academic.exams@1#getExams'>;
export type CalendarReadResult = CapabilityResponse<'academic.calendar@1#getCalendar'>;
export type ProgressReadResult = CapabilityResponse<'academic.progress@1#getProgress'>;
export type HomeworkReadResult = CapabilityResponse<'academic.homework@1#getHomework'>;
export type CourseResourcesReadResult =
  CapabilityResponse<'academic.resources@1#getCourseResources'>;
export type CampusRequestResult = CapabilityResponse<'campus.request@1#request'>;
export type MailFoldersReadResult = CapabilityResponse<'mail.read@1#listFolders'>;
export type MailMessagesReadResult = CapabilityResponse<'mail.read@1#listMessages'>;
export type MailMessageReadResult = CapabilityResponse<'mail.read@1#getMessage'>;
export type AndroidAccessibilityStatus = CapabilityResponse<'android.accessibility.events@1#getStatus'>;
export type AndroidAccessibilitySubscription = CapabilityResponse<'android.accessibility.events@1#subscribe'>;
export type AndroidAccessibilityEvent = CapabilityEventData<'android.accessibility.events@1#received'>;
export type AndroidAccessibilityNode = CapabilityResponse<'android.accessibility.nodes@1#get'>;
export type AndroidAccessibilityNodeList = CapabilityResponse<'android.accessibility.nodes@1#find'>;
export type AndroidPackageInfo = CapabilityResponse<'android.packages.read@1#get'>;
export type AndroidPackageList = CapabilityResponse<'android.packages.read@1#list'>;
export type AndroidResolvedActivities = CapabilityResponse<'android.packages.read@1#resolveIntent'>;
export type AndroidSettingsOpenResult = CapabilityResponse<'android.settings.open@1#open'>;
export type AndroidNetworkStatus = CapabilityResponse<'android.network.status@1#getStatus'>;
export type AndroidBatteryStatus = CapabilityResponse<'android.battery.status@1#getStatus'>;
export type AndroidNetworkChangedEvent = CapabilityEventData<'android.network.status@1#changed'>;
export type AndroidBatteryChangedEvent = CapabilityEventData<'android.battery.status@1#changed'>;
export type AndroidSensorChangedEvent = CapabilityEventData<'android.sensors.read@1#changed'>;
export type CommandReceipt =
  | CapabilityResponse<'academic.userCourses.command@1#save'>
  | CapabilityResponse<'academic.homework.submit@1#submit'>
  | CapabilityResponse<'mail.send@1#send'>;

type RuntimeEventRoute = Extract<CapabilityEventRoute, `runtime.lifecycle@1#${string}`>;
export type RuntimeEventName =
  RuntimeEventRoute extends `runtime.lifecycle@1#${infer Name}` ? Name : never;
export type RuntimeEventData<Name extends RuntimeEventName> = CapabilityEventData<
  Extract<RuntimeEventRoute, `runtime.lifecycle@1#${Name}`>
>;
export type RuntimeEventListener<Name extends RuntimeEventName> = (
  data: RuntimeEventData<Name>,
  envelope: PluginEventV2
) => boolean | void | Promise<boolean | void>;

export interface BjtuPluginSdk {
  readonly runtime: {
    handshake(options?: InvokeOptions): Promise<
      CapabilityResponse<'runtime.lifecycle@1#handshake'>
    >;
    ready(options?: InvokeOptions): Promise<void>;
    close(options?: InvokeOptions): Promise<void>;
    on<Name extends RuntimeEventName>(
      event: Name,
      listener: RuntimeEventListener<Name>
    ): () => void;
  };
  readonly configuration: {
    get(key: string, options?: InvokeOptions): Promise<string | null>;
  };
  readonly network: {
    request(request: NetworkRequest, options?: InvokeOptions): Promise<NetworkResponse>;
    requestWithCookies(request: CapabilityRequest<'network.request@2#request'>, options?: InvokeOptions): Promise<CapabilityResponse<'network.request@2#request'>>;
  };
  readonly storage: {
    readonly kv: {
      get(key: string, options?: InvokeOptions): Promise<KvGetResult>;
      set(key: string, value: unknown, ifRevision?: number, options?: InvokeOptions): Promise<KvSetResult>;
      remove(key: string, ifRevision?: number, options?: InvokeOptions): Promise<KvRemoveResult>;
      keys(options?: InvokeOptions): Promise<KvKeysResult>;
      usage(options?: InvokeOptions): Promise<KvUsageResult>;
      batch(operations: Array<Record<string, unknown>>, options?: InvokeOptions): Promise<KvBatchResult>;
      transaction(
        ifRevision: number,
        operations: Array<Record<string, unknown>>,
        options?: InvokeOptions
      ): Promise<KvTransactionResult>;
      export(options?: InvokeOptions): Promise<ResourceHandle>;
      import(handle: string, ifRevision?: number, options?: InvokeOptions): Promise<KvImportResult>;
      watch(listener: (data: KvChangedEvent) => void): () => void;
    };
    readonly blob: {
      put(data: ArrayBuffer, contentType: string, options?: InvokeOptions): Promise<ResourceHandle>;
      getInfo(handle: string, options?: InvokeOptions): Promise<ResourceHandle>;
      delete(handle: string, options?: InvokeOptions): Promise<boolean>;
    };
  };
  readonly cache: {
    put(
      key: string,
      data: ArrayBuffer,
      contentType: string,
      options?: InvokeOptions & { pin?: boolean }
    ): Promise<ResourceHandle>;
    promote(
      handle: string,
      key: string,
      options?: InvokeOptions & { pinned?: boolean }
    ): Promise<ResourceHandle>;
    deleteHandle(handle: string, options?: InvokeOptions): Promise<boolean>;
    match(key: string, options?: InvokeOptions): Promise<ResourceHandle | null>;
    delete(
      key: string,
      options?: InvokeOptions
    ): Promise<CapabilityResponse<'cache.resource@1#delete'>>;
    pin(
      key: string,
      pinned: boolean,
      options?: InvokeOptions
    ): Promise<CapabilityResponse<'cache.resource@1#pin'>>;
    usage(options?: InvokeOptions): Promise<CapabilityResponse<'cache.resource@1#usage'>>;
  };
  readonly navigation: {
    open(url: string, options?: InvokeOptions): Promise<boolean>;
  };
  readonly campus: {
    getProfile(options?: InvokeOptions & { forceRefresh?: boolean }): Promise<StudentProfileReadResult>;
    getTimetable(options?: InvokeOptions & { forceRefresh?: boolean }): Promise<TimetableReadResult>;
    getScores(
      request?: { term?: string; courseType?: string; forceRefresh?: boolean },
      options?: InvokeOptions
    ): Promise<ScoresReadResult>;
    getHistoryScores(
      request?: { term?: string; forceRefresh?: boolean },
      options?: InvokeOptions
    ): Promise<ScoresReadResult>;
    getExams(
      request?: { term?: string; forceRefresh?: boolean },
      options?: InvokeOptions
    ): Promise<ExamsReadResult>;
    getCalendar(
      request?: { month?: string; forceRefresh?: boolean },
      options?: InvokeOptions
    ): Promise<CalendarReadResult>;
    getProgress(options?: InvokeOptions & { forceRefresh?: boolean }): Promise<ProgressReadResult>;
    getHomework(
      request?: { status?: string; forceRefresh?: boolean },
      options?: InvokeOptions
    ): Promise<HomeworkReadResult>;
    getCourseResources(
      request: CapabilityRequest<'academic.resources@1#getCourseResources'>,
      options?: InvokeOptions
    ): Promise<CourseResourcesReadResult>;
    request(
      request: CapabilityRequest<'campus.request@1#request'>,
      options?: InvokeOptions
    ): Promise<CampusRequestResult>;
    saveUserCourse(
      idempotencyKey: string,
      course: Record<string, unknown>,
      options?: InvokeOptions
    ): Promise<CapabilityResponse<'academic.userCourses.command@1#save'>>;
    deleteUserCourse(
      idempotencyKey: string,
      id: number,
      options?: InvokeOptions
    ): Promise<CapabilityResponse<'academic.userCourses.command@1#delete'>>;
    submitHomework(
      request: CapabilityRequest<'academic.homework.submit@1#submit'>,
      options?: InvokeOptions
    ): Promise<CapabilityResponse<'academic.homework.submit@1#submit'>>;
  };
  readonly mail: {
    listFolders(options?: InvokeOptions & { forceRefresh?: boolean }): Promise<MailFoldersReadResult>;
    listMessages(
      request?: CapabilityRequest<'mail.read@1#listMessages'>,
      options?: InvokeOptions
    ): Promise<MailMessagesReadResult>;
    getMessage(
      messageId: string,
      mailbox?: string,
      options?: InvokeOptions
    ): Promise<MailMessageReadResult>;
    send(
      request: CapabilityRequest<'mail.send@1#send'>,
      options?: InvokeOptions
    ): Promise<CapabilityResponse<'mail.send@1#send'>>;
  };
  readonly android: {
    readonly accessibility: {
      readonly events: {
        getStatus(options?: InvokeOptions): Promise<AndroidAccessibilityStatus>;
        subscribe(
          request: CapabilityRequest<'android.accessibility.events@1#subscribe'>,
          options?: InvokeOptions
        ): Promise<AndroidAccessibilitySubscription>;
        unsubscribe(
          subscriptionId: string,
          options?: InvokeOptions
        ): Promise<CapabilityResponse<'android.accessibility.events@1#unsubscribe'>>;
        listSubscriptions(
          options?: InvokeOptions
        ): Promise<CapabilityResponse<'android.accessibility.events@1#listSubscriptions'>>;
        onReceived(
          listener: (
            data: AndroidAccessibilityEvent,
            envelope: PluginEventV2
          ) => boolean | void | Promise<boolean | void>
        ): () => void;
      };
      readonly nodes: {
        getRoot(
          request?: CapabilityRequest<'android.accessibility.nodes@1#getRoot'>,
          options?: InvokeOptions
        ): Promise<CapabilityResponse<'android.accessibility.nodes@1#getRoot'>>;
        find(
          request: CapabilityRequest<'android.accessibility.nodes@1#find'>,
          options?: InvokeOptions
        ): Promise<AndroidAccessibilityNodeList>;
        get(nodeId: string, options?: InvokeOptions): Promise<AndroidAccessibilityNode>;
      };
      readonly actions: {
        performNode(
          request: CapabilityRequest<'android.accessibility.actions@1#performNode'>,
          options?: InvokeOptions
        ): Promise<CapabilityResponse<'android.accessibility.actions@1#performNode'>>;
        performGlobal(
          request: CapabilityRequest<'android.accessibility.actions@1#performGlobal'>,
          options?: InvokeOptions
        ): Promise<CapabilityResponse<'android.accessibility.actions@1#performGlobal'>>;
        dispatchGesture(
          request: CapabilityRequest<'android.accessibility.actions@1#dispatchGesture'>,
          options?: InvokeOptions
        ): Promise<CapabilityResponse<'android.accessibility.actions@1#dispatchGesture'>>;
      };
    };
    readonly packages: {
      list(
        request?: CapabilityRequest<'android.packages.read@1#list'>,
        options?: InvokeOptions
      ): Promise<AndroidPackageList>;
      get(packageName: string, options?: InvokeOptions): Promise<AndroidPackageInfo>;
      resolveIntent(
        request: CapabilityRequest<'android.packages.read@1#resolveIntent'>,
        options?: InvokeOptions
      ): Promise<AndroidResolvedActivities>;
    };
    readonly settings: {
      open(
        request: CapabilityRequest<'android.settings.open@1#open'>,
        options?: InvokeOptions
      ): Promise<AndroidSettingsOpenResult>;
    };
    readonly credentials: {
      import(request: CapabilityRequest<'android.credentials.import@1#import'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.credentials.import@1#import'>>;
    };
    readonly background: {
      task: {
        schedule(request: CapabilityRequest<'android.background.task@1#schedule'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.background.task@1#schedule'>>;
        cancel(request: CapabilityRequest<'android.background.task@1#cancel'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.background.task@1#cancel'>>;
        list(options?: InvokeOptions): Promise<CapabilityResponse<'android.background.task@1#list'>>;
        onTick(listener: (data: CapabilityEventData<'android.background.task@1#tick'>, envelope: PluginEventV2) => boolean | void | Promise<boolean | void>): () => void;
      };
    };
    readonly device: {
      getInfo(options?: InvokeOptions): Promise<CapabilityResponse<'android.device.info@1#getInfo'>>;
    };
    readonly network: {
      getStatus(options?: InvokeOptions): Promise<AndroidNetworkStatus>;
      getAddresses(request?: CapabilityRequest<'android.network.status@1#getAddresses'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.network.status@1#getAddresses'>>;
      subscribe(
        request?: CapabilityRequest<'android.network.status@1#subscribe'>,
        options?: InvokeOptions
      ): Promise<CapabilityResponse<'android.network.status@1#subscribe'>>;
      unsubscribe(subscriptionId: string, options?: InvokeOptions): Promise<CapabilityResponse<'android.network.status@1#unsubscribe'>>;
      onChanged(listener: (data: AndroidNetworkChangedEvent, envelope: PluginEventV2) => boolean | void | Promise<boolean | void>): () => void;
    };
    readonly battery: {
      getStatus(options?: InvokeOptions): Promise<AndroidBatteryStatus>;
      subscribe(
        request?: CapabilityRequest<'android.battery.status@1#subscribe'>,
        options?: InvokeOptions
      ): Promise<CapabilityResponse<'android.battery.status@1#subscribe'>>;
      unsubscribe(subscriptionId: string, options?: InvokeOptions): Promise<CapabilityResponse<'android.battery.status@1#unsubscribe'>>;
      onChanged(listener: (data: AndroidBatteryChangedEvent, envelope: PluginEventV2) => boolean | void | Promise<boolean | void>): () => void;
    };
    readonly haptics: {
      perform(request: CapabilityRequest<'android.haptics.perform@1#perform'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.haptics.perform@1#perform'>>;
    };
    readonly files: {
      pick(request?: CapabilityRequest<'android.files.pick@1#pick'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.files.pick@1#pick'>>;
      save(request: CapabilityRequest<'android.files.save@1#save'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.files.save@1#save'>>;
    };
    readonly media: {
      pick(request?: CapabilityRequest<'android.media.pick@1#pick'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.media.pick@1#pick'>>;
    };
    readonly share: {
      open(request: CapabilityRequest<'android.share.open@1#open'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.share.open@1#open'>>;
    };
    readonly notifications: {
      getStatus(options?: InvokeOptions): Promise<CapabilityResponse<'android.notifications.post@1#getStatus'>>;
      show(request: CapabilityRequest<'android.notifications.post@1#show'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.notifications.post@1#show'>>;
      schedule(request: CapabilityRequest<'android.notifications.post@1#schedule'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.notifications.post@1#schedule'>>;
      cancel(request: CapabilityRequest<'android.notifications.post@1#cancel'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.notifications.post@1#cancel'>>;
    };
    readonly location: {
      getStatus(options?: InvokeOptions): Promise<CapabilityResponse<'android.location.read@1#getStatus'>>;
      getCurrent(request?: CapabilityRequest<'android.location.read@1#getCurrent'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.location.read@1#getCurrent'>>;
    };
    readonly calendar: {
      list(request: CapabilityRequest<'android.calendar.read@1#list'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.calendar.read@1#list'>>;
      create(request: CapabilityRequest<'android.calendar.write@1#create'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.calendar.write@1#create'>>;
      update(request: CapabilityRequest<'android.calendar.write@1#update'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.calendar.write@1#update'>>;
      delete(request: CapabilityRequest<'android.calendar.write@1#delete'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.calendar.write@1#delete'>>;
    };
    readonly camera: {
      capturePhoto(options?: InvokeOptions): Promise<CapabilityResponse<'android.camera.capture@1#capturePhoto'>>;
    };
    readonly audio: {
      start(request: CapabilityRequest<'android.audio.record@1#start'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.audio.record@1#start'>>;
      stop(request: CapabilityRequest<'android.audio.record@1#stop'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.audio.record@1#stop'>>;
    };
    readonly sensors: {
      list(options?: InvokeOptions): Promise<CapabilityResponse<'android.sensors.read@1#list'>>;
      subscribe(request: CapabilityRequest<'android.sensors.read@1#subscribe'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.sensors.read@1#subscribe'>>;
      unsubscribe(subscriptionId: string, options?: InvokeOptions): Promise<CapabilityResponse<'android.sensors.read@1#unsubscribe'>>;
      onChanged(listener: (data: AndroidSensorChangedEvent, envelope: PluginEventV2) => boolean | void | Promise<boolean | void>): () => void;
    };
    readonly session: {
      keepAlive: {
        acquire(request: CapabilityRequest<'android.session.keepAlive@1#acquire'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.session.keepAlive@1#acquire'>>;
        renew(request: CapabilityRequest<'android.session.keepAlive@1#renew'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.session.keepAlive@1#renew'>>;
        release(request: CapabilityRequest<'android.session.keepAlive@1#release'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.session.keepAlive@1#release'>>;
        getStatus(options?: InvokeOptions): Promise<CapabilityResponse<'android.session.keepAlive@1#getStatus'>>;
        onEnded(listener: (data: CapabilityEventData<'android.session.keepAlive@1#ended'>, envelope: PluginEventV2) => boolean | void | Promise<boolean | void>): () => void;
      };
    };
    readonly biometric: {
      getStatus(options?: InvokeOptions): Promise<CapabilityResponse<'android.biometric.verify@1#getStatus'>>;
      verify(request: CapabilityRequest<'android.biometric.verify@1#verify'>, options?: InvokeOptions): Promise<CapabilityResponse<'android.biometric.verify@1#verify'>>;
    };
  };
}

export interface BjtuPluginMigrationSdk {
  readonly storage: {
    get(key: string): Promise<unknown>;
    set(key: string, value: unknown): Promise<unknown>;
    remove(key: string): Promise<unknown>;
    keys(): Promise<unknown>;
    usage(): Promise<unknown>;
    clear(): Promise<unknown>;
  };
  commit(): Promise<void>;
}

/**
 * Creates the restricted client available only inside a declared migration
 * entrypoint. Normal runtime, network, and command capabilities stay unavailable.
 */
export function createBjtuPluginMigrationSdk(): BjtuPluginMigrationSdk;
export function createBjtuPluginMigrationSdk(
  bridge: PluginMigrationBridge = getMigrationBridge()
): BjtuPluginMigrationSdk {
  const invoke = async (
    capability: 'storage.kv@2' | 'runtime.migration@1',
    method: string,
    params: Record<string, unknown> = {}
  ): Promise<unknown> => {
    const response = await bridge.invoke(capability, method, params);
    if (!response.ok) {
      throw new BjtuPluginError(response.error.code, response.error.message, {
        retryable: response.error.retryable,
        details: response.error.details
      });
    }
    return response.result;
  };
  return {
    storage: {
      get: (key) => invoke('storage.kv@2', 'get', { key }),
      set: (key, value) => invoke('storage.kv@2', 'set', { key, value }),
      remove: (key) => invoke('storage.kv@2', 'remove', { key }),
      keys: () => invoke('storage.kv@2', 'keys'),
      usage: () => invoke('storage.kv@2', 'usage'),
      clear: () => invoke('storage.kv@2', 'clear')
    },
    commit: async () => {
      await invoke('runtime.migration@1', 'commit');
    }
  };
}

export function createBjtuPluginSdk(): BjtuPluginSdk;
export function createBjtuPluginSdk(
  transport: PluginTransport = new WebViewBridgeTransport()
): BjtuPluginSdk {
  let negotiatedBinaryTransport: BinaryTransport | undefined;
  const invoke = async <Route extends CapabilityRoute>(
    route: Route,
    params: CapabilityRequest<Route>,
    options: InvokeOptions = {},
    binary?: ArrayBuffer
  ): Promise<CapabilityResponse<Route>> => {
    const [capability, method] = route.split('#') as [CapabilityId, string];
    const requestId = createRequestId();
    if (binary !== undefined && negotiatedBinaryTransport === undefined) {
      throw new BjtuPluginError(
        'capability_unavailable',
        'Binary transport is not negotiated. Call runtime.handshake() before Blob/Cache writes.'
      );
    }
    if (options.signal?.aborted) {
      throw new BjtuPluginError('user_cancelled', 'The request was cancelled before dispatch.');
    }
    const removeEventListener = options.onProgress
      ? transport.subscribe((event) => {
          if (
            event.requestId === requestId &&
            event.event === 'progress' &&
            isProgress(event.data)
          ) {
            options.onProgress?.(event.data);
          }
        })
      : () => undefined;
    const cancelTransportRequest = () => {
      try {
        transport.cancel(requestId);
      } catch {
        // Cancellation is already represented by the SDK promise. A detached
        // or closing native bridge must not surface as an uncaught exception.
      }
    };
    let rejectCancellation: ((error: BjtuPluginError) => void) | undefined;
    const cancellation = new Promise<never>((_, reject) => {
      rejectCancellation = reject;
    });
    const onAbort = () => {
      rejectCancellation?.(
        new BjtuPluginError('user_cancelled', 'The request was cancelled.')
      );
      cancelTransportRequest();
    };
    options.signal?.addEventListener('abort', onAbort, { once: true });
    const timeoutMs = resolveTimeoutMs(capability, params, options.timeoutMs);
    let timeoutHandle: ReturnType<typeof setTimeout> | undefined;
    const timeout = new Promise<never>((_, reject) => {
      if (timeoutMs === undefined) return;
      timeoutHandle = setTimeout(() => {
        const error = new BjtuPluginError(
          'request_timeout',
          `The ${capability} request exceeded its ${timeoutMs} ms deadline.`,
          { retryable: true }
        );
        reject(error);
        cancelTransportRequest();
      }, timeoutMs);
    });
    try {
      const response = await Promise.race([
        transport.send(
          {
            protocolVersion: PROTOCOL_VERSION,
            requestId,
            capability,
            method,
            params
          },
          binary
        ),
        timeout,
        cancellation
      ]);
      if (options.signal?.aborted) {
        throw new BjtuPluginError('user_cancelled', 'The request was cancelled.');
      }
      if (!response.ok) {
        throw new BjtuPluginError(response.error.code, response.error.message, {
          retryable: response.error.retryable,
          details: response.error.details
        });
      }
      return response.result as CapabilityResponse<Route>;
    } catch (error) {
      if (options.signal?.aborted) {
        throw new BjtuPluginError('user_cancelled', 'The request was cancelled.', { cause: error });
      }
      if (error instanceof BjtuPluginError) throw error;
      if (error instanceof WebViewTransportError) {
        throw new BjtuPluginError(error.code, error.message, { cause: error });
      }
      throw new BjtuPluginError('capability_unavailable', 'Plugin transport failed.', {
        cause: error
      });
    } finally {
      if (timeoutHandle !== undefined) clearTimeout(timeoutHandle);
      removeEventListener();
      options.signal?.removeEventListener('abort', onAbort);
    }
  };

  const subscribe = <TData>(
    capability: CapabilityId,
    event: string,
    listener: (
      data: TData,
      envelope: PluginEventV2
    ) => boolean | void | Promise<boolean | void>
  ) =>
    transport.subscribe((envelope) => {
      if (envelope.capability === capability && envelope.event === event) {
        return listener(envelope.data as TData, envelope);
      }
      return false;
    });

  return {
    runtime: {
      handshake: async (options) => {
        negotiatedBinaryTransport = undefined;
        transport.configureBinaryTransport?.(undefined);
        const result = normalizeHandshake(
          await invoke('runtime.lifecycle@1#handshake', { sdkVersion: SDK_VERSION }, options)
        );
        negotiatedBinaryTransport = result.preferredBinaryTransport;
        transport.configureBinaryTransport?.(negotiatedBinaryTransport);
        return result;
      },
      ready: async (options) => {
        await invoke('runtime.lifecycle@1#ready', {}, options);
      },
      close: async (options) => {
        await invoke('runtime.lifecycle@1#close', {}, options);
      },
      on: (event, listener) =>
        subscribe(
          'runtime.lifecycle@1',
          event,
          listener as (
            data: unknown,
            envelope: PluginEventV2
          ) => boolean | void | Promise<boolean | void>
        )
    },
    configuration: {
      get: async (key, options) =>
        (await invoke('configuration.read@1#get', { key }, options)).value
    },
    network: {
      request: (request, options) =>
        invoke('network.request@1#request', request, options),
      requestWithCookies: (request, options) => invoke('network.request@2#request', request, options)
    },
    storage: {
      kv: {
        get: (key, options) => invoke('storage.kv@2#get', { key }, options),
        set: (key, value, ifRevision, options) =>
          invoke(
            'storage.kv@2#set',
            {
              key,
              value,
              ...(ifRevision === undefined ? {} : { ifRevision })
            },
            options
          ),
        remove: (key, ifRevision, options) =>
          invoke(
            'storage.kv@2#remove',
            {
              key,
              ...(ifRevision === undefined ? {} : { ifRevision })
            },
            options
          ),
        keys: (options) => invoke('storage.kv@2#keys', {}, options),
        usage: (options) => invoke('storage.kv@2#usage', {}, options),
        batch: (operations, options) => invoke('storage.kv@2#batch', { operations }, options),
        transaction: (ifRevision, operations, options) =>
          invoke('storage.kv@2#transaction', { ifRevision, operations }, options),
        export: (options) => invoke('storage.kv@2#export', {}, options),
        import: (handle, ifRevision, options) =>
          invoke(
            'storage.kv@2#import',
            {
              handle,
              ...(ifRevision === undefined ? {} : { ifRevision })
            },
            options
          ),
        watch: (listener) =>
          subscribe('storage.kv@2', 'changed', (data) => listener(data as KvChangedEvent))
      },
      blob: {
        put: (data, contentType, options) =>
          invoke('storage.blob@1#put', { contentType, size: data.byteLength }, options, data),
        getInfo: (handle, options) =>
          invoke('storage.blob@1#getInfo', { handle }, options),
        delete: async (handle, options) =>
          (await invoke('storage.blob@1#delete', { handle }, options)).deleted
      }
    },
    cache: {
      put: (key, data, contentType, options) =>
        invoke(
          'cache.resource@1#put',
          {
            key,
            contentType,
            size: data.byteLength,
            ...(options?.pin === undefined ? {} : { pin: options.pin })
          },
          options,
          data
        ),
      match: (key, options) =>
        invoke('cache.resource@1#match', { key }, options) as Promise<ResourceHandle | null>,
      promote: (handle, key, options) =>
        invoke(
          'cache.resource@1#promote',
          {
            handle,
            key,
            ...(options?.pinned === undefined ? {} : { pinned: options.pinned })
          },
          options
        ),
      deleteHandle: async (handle, options) =>
        (await invoke('cache.resource@1#deleteHandle', { handle }, options)).deleted,
      delete: (key, options) => invoke('cache.resource@1#delete', { key }, options),
      pin: (key, pinned, options) =>
        invoke('cache.resource@1#pin', { key, pinned }, options),
      usage: (options) => invoke('cache.resource@1#usage', {}, options)
    },
    navigation: {
      open: async (url, options) =>
        (await invoke('navigation.external@1#open', { url }, options)).opened
    },
    campus: {
      getProfile: (options = {}) =>
        invoke(
          'identity.profile@1#getProfile',
          { forceRefresh: options.forceRefresh },
          options
        ),
      getTimetable: (options = {}) =>
        invoke(
          'academic.timetable@1#getTimetable',
          { forceRefresh: options.forceRefresh },
          options
        ),
      getScores: (request = {}, options) =>
        invoke('academic.scores@1#getScores', request, options),
      getHistoryScores: (request = {}, options) =>
        invoke('academic.scores@1#getHistoryScores', request, options),
      getExams: (request = {}, options) =>
        invoke('academic.exams@1#getExams', request, options),
      getCalendar: (request = {}, options) =>
        invoke('academic.calendar@1#getCalendar', request, options),
      getProgress: (options = {}) =>
        invoke(
          'academic.progress@1#getProgress',
          { forceRefresh: options.forceRefresh },
          options
        ),
      getHomework: (request = {}, options) =>
        invoke('academic.homework@1#getHomework', request, options),
      getCourseResources: (request, options) =>
        invoke(
          'academic.resources@1#getCourseResources',
          request,
          options
        ),
      request: (request, options) =>
        invoke(
          'campus.request@1#request',
          request,
          options
        ),
      saveUserCourse: (idempotencyKey, course, options) =>
        invoke('academic.userCourses.command@1#save', { idempotencyKey, course }, options),
      deleteUserCourse: (idempotencyKey, id, options) =>
        invoke('academic.userCourses.command@1#delete', { idempotencyKey, id }, options),
      submitHomework: (request, options) =>
        invoke('academic.homework.submit@1#submit', request, options)
    },
    mail: {
      listFolders: (options = {}) =>
        invoke('mail.read@1#listFolders', { forceRefresh: options.forceRefresh }, options),
      listMessages: (request = {}, options) =>
        invoke('mail.read@1#listMessages', request, options),
      getMessage: (messageId, mailbox, options) =>
        invoke(
          'mail.read@1#getMessage',
          {
            messageId,
            ...(mailbox === undefined ? {} : { mailbox })
          },
          options
        ),
      send: (request, options) =>
        invoke('mail.send@1#send', request, options)
    },
    android: {
      accessibility: {
        events: {
          getStatus: (options) =>
            invoke('android.accessibility.events@1#getStatus', {}, options),
          subscribe: (request, options) =>
            invoke('android.accessibility.events@1#subscribe', request, options),
          unsubscribe: (subscriptionId, options) =>
            invoke('android.accessibility.events@1#unsubscribe', { subscriptionId }, options),
          listSubscriptions: (options) =>
            invoke('android.accessibility.events@1#listSubscriptions', {}, options),
          onReceived: (listener) =>
            subscribe('android.accessibility.events@1', 'received', listener)
        },
        nodes: {
          getRoot: (request = {}, options) =>
            invoke('android.accessibility.nodes@1#getRoot', request, options),
          find: (request, options) =>
            invoke('android.accessibility.nodes@1#find', request, options),
          get: (nodeId, options) =>
            invoke('android.accessibility.nodes@1#get', { nodeId }, options)
        },
        actions: {
          performNode: (request, options) =>
            invoke('android.accessibility.actions@1#performNode', request, options),
          performGlobal: (request, options) =>
            invoke('android.accessibility.actions@1#performGlobal', request, options),
          dispatchGesture: (request, options) =>
            invoke('android.accessibility.actions@1#dispatchGesture', request, options)
        }
      },
      packages: {
        list: (request = {}, options) =>
          invoke('android.packages.read@1#list', request, options),
        get: (packageName, options) =>
          invoke('android.packages.read@1#get', { packageName }, options),
        resolveIntent: (request, options) =>
          invoke('android.packages.read@1#resolveIntent', request, options)
      },
      settings: {
        open: (request, options) =>
          invoke('android.settings.open@1#open', request, options)
      },
      credentials: {
        import: (request, options) => invoke('android.credentials.import@1#import', request, options)
      },
      background: {
        task: {
          schedule: (request, options) => invoke('android.background.task@1#schedule', request, options),
          cancel: (request, options) => invoke('android.background.task@1#cancel', request, options),
          list: (options) => invoke('android.background.task@1#list', {}, options),
          onTick: (listener) => subscribe('android.background.task@1', 'tick', listener)
        }
      },
      device: {
        getInfo: (options) => invoke('android.device.info@1#getInfo', {}, options)
      },
      network: {
        getStatus: (options) => invoke('android.network.status@1#getStatus', {}, options),
        getAddresses: (request = {}, options) => invoke('android.network.status@1#getAddresses', request, options),
        subscribe: (request = {}, options) =>
          invoke('android.network.status@1#subscribe', request, options),
        unsubscribe: (subscriptionId, options) =>
          invoke('android.network.status@1#unsubscribe', { subscriptionId }, options),
        onChanged: (listener) => subscribe('android.network.status@1', 'changed', listener)
      },
      battery: {
        getStatus: (options) => invoke('android.battery.status@1#getStatus', {}, options),
        subscribe: (request = {}, options) =>
          invoke('android.battery.status@1#subscribe', request, options),
        unsubscribe: (subscriptionId, options) =>
          invoke('android.battery.status@1#unsubscribe', { subscriptionId }, options),
        onChanged: (listener) => subscribe('android.battery.status@1', 'changed', listener)
      },
      haptics: {
        perform: (request, options) => invoke('android.haptics.perform@1#perform', request, options)
      },
      files: {
        pick: (request = {}, options) => invoke('android.files.pick@1#pick', request, options),
        save: (request, options) => invoke('android.files.save@1#save', request, options)
      },
      media: {
        pick: (request = {}, options) => invoke('android.media.pick@1#pick', request, options)
      },
      share: {
        open: (request, options) => invoke('android.share.open@1#open', request, options)
      },
      notifications: {
        getStatus: (options) => invoke('android.notifications.post@1#getStatus', {}, options),
        show: (request, options) => invoke('android.notifications.post@1#show', request, options),
        schedule: (request, options) => invoke('android.notifications.post@1#schedule', request, options),
        cancel: (request, options) => invoke('android.notifications.post@1#cancel', request, options)
      },
      location: {
        getStatus: (options) => invoke('android.location.read@1#getStatus', {}, options),
        getCurrent: (request = {}, options) => invoke('android.location.read@1#getCurrent', request, options)
      },
      calendar: {
        list: (request, options) => invoke('android.calendar.read@1#list', request, options),
        create: (request, options) => invoke('android.calendar.write@1#create', request, options),
        update: (request, options) => invoke('android.calendar.write@1#update', request, options),
        delete: (request, options) => invoke('android.calendar.write@1#delete', request, options)
      },
      camera: {
        capturePhoto: (options) => invoke('android.camera.capture@1#capturePhoto', {}, options)
      },
      audio: {
        start: (request, options) => invoke('android.audio.record@1#start', request, options),
        stop: (request, options) => invoke('android.audio.record@1#stop', request, options)
      },
      sensors: {
        list: (options) => invoke('android.sensors.read@1#list', {}, options),
        subscribe: (request, options) => invoke('android.sensors.read@1#subscribe', request, options),
        unsubscribe: (subscriptionId, options) =>
          invoke('android.sensors.read@1#unsubscribe', { subscriptionId }, options),
        onChanged: (listener) => subscribe('android.sensors.read@1', 'changed', listener)
      },
      session: {
        keepAlive: {
          acquire: (request, options) => invoke('android.session.keepAlive@1#acquire', request, options),
          renew: (request, options) => invoke('android.session.keepAlive@1#renew', request, options),
          release: (request, options) => invoke('android.session.keepAlive@1#release', request, options),
          getStatus: (options) => invoke('android.session.keepAlive@1#getStatus', {}, options),
          onEnded: (listener) => subscribe('android.session.keepAlive@1', 'ended', listener)
        }
      },
      biometric: {
        getStatus: (options) => invoke('android.biometric.verify@1#getStatus', {}, options),
        verify: (request, options) => invoke('android.biometric.verify@1#verify', request, options)
      }
    }
  };
}

export function assertCapabilityId(value: string): asserts value is CapabilityId {
  if (!(CAPABILITY_IDS as readonly string[]).includes(value)) {
    throw new BjtuPluginError('invalid_request', `Unknown capability: ${value}`);
  }
}

function createRequestId(): string {
  return globalThis.crypto?.randomUUID?.() ??
    `bjtu-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

function resolveTimeoutMs(
  capability: CapabilityId,
  params: unknown,
  override: number | undefined
): number | undefined {
  const descriptor = CAPABILITY_REGISTRY.capabilities.find(
    (item) => item.id === capability
  ) as
    | {
        timeoutMs: number;
        maxTimeoutMs?: number;
      }
    | undefined;
  if (!descriptor) {
    throw new BjtuPluginError('capability_unavailable', `Unknown capability: ${capability}`);
  }
  const requestTimeout =
    (capability === 'network.request@1' || capability === 'network.request@2') && isObject(params) && typeof params.timeoutMs === 'number'
      ? params.timeoutMs
      : undefined;
  const timeoutMs = override ?? requestTimeout ?? descriptor.timeoutMs;
  if (timeoutMs === 0 && override === undefined && requestTimeout === undefined) return undefined;
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs <= 0) {
    throw new BjtuPluginError('invalid_request', 'timeoutMs must be a positive integer.');
  }
  const maximum = descriptor.maxTimeoutMs ?? descriptor.timeoutMs;
  if (timeoutMs > maximum) {
    throw new BjtuPluginError(
      'invalid_request',
      `timeoutMs exceeds the ${maximum} ms capability limit.`
    );
  }
  return timeoutMs;
}

function getMigrationBridge(): PluginMigrationBridge {
  const bridge = globalThis[
    PRIVATE_MIGRATION_BRIDGE_KEY as keyof typeof globalThis
  ] as PluginMigrationBridge | undefined;
  if (!bridge || typeof bridge.invoke !== 'function') {
    throw new BjtuPluginError(
      'migration_failed',
      'BJTU plugin migration transport is unavailable.'
    );
  }
  return bridge;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object';
}

function isProgress(value: unknown): value is PluginProgress {
  return (
    isObject(value) &&
    typeof value.loaded === 'number' &&
    (value.total === undefined || typeof value.total === 'number') &&
    (value.phase === undefined || typeof value.phase === 'string')
  );
}

function normalizeHandshake(
  value: CapabilityResponse<'runtime.lifecycle@1#handshake'>
): CapabilityResponse<'runtime.lifecycle@1#handshake'> {
  const raw = value as unknown as Record<string, unknown>;
  const advertised = Array.isArray(raw.binaryTransports)
    ? raw.binaryTransports.filter(isBinaryTransport)
    : [];
  const binaryTransports = [...new Set<BinaryTransport>(advertised)];
  if (binaryTransports.length === 0 && raw.binaryTransport === true) {
    binaryTransports.push('arraybuffer');
  }
  const preferredBinaryTransport = isBinaryTransport(raw.preferredBinaryTransport) &&
      binaryTransports.includes(raw.preferredBinaryTransport)
    ? raw.preferredBinaryTransport
    : binaryTransports[0];
  const { binaryTransport: _legacyBinaryTransport, ...rest } = raw;
  return {
    ...rest,
    binaryTransports,
    ...(preferredBinaryTransport === undefined ? {} : { preferredBinaryTransport })
  } as CapabilityResponse<'runtime.lifecycle@1#handshake'>;
}

function isBinaryTransport(value: unknown): value is BinaryTransport {
  return value === 'arraybuffer' || value === 'base64url-chunks-v1';
}

export type { CapabilityMethodMap };
