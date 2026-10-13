import {
	createLocalMemory,
	createLocalNote,
	createLocalTask,
	deleteLocalMemory,
	getLocalChat,
	getLocalFileRecord,
	listLocalChats,
	listLocalFiles,
	listLocalTasks,
	searchLocalKnowledge,
	searchLocalMemories,
	searchLocalNotes,
	updateLocalTask,
	type LocalChatRecord,
	type LocalFileRecord,
	type LocalTaskRecord
} from '../db';
import {
	capturePhoto,
	cancelNotification,
	createCalendarEvent,
	deleteCalendarEvent,
	getCurrentLocation,
	getDeviceContext,
	listCalendarEvents,
	pickMedia,
	scheduleNotification,
	startAudioRecording,
	stopAudioRecording,
	supportsNativeAndroidTools,
	updateCalendarEvent
} from '../android-tools';
import {
	confirmNativeMailSend,
	executeNativeAgentTool,
	listNativeAgentTools,
	supportsNativeAgentTools
} from '../native-agent-tools';
import type { NativeAgentToolPolicy } from '../native-agent-tools';

type JsonRecord = Record<string, any>;

export type LocalToolDefinition = {
	type: 'function';
	function: {
		name: string;
		description: string;
		parameters: JsonRecord;
	};
	category:
		| 'time'
		| 'math'
		| 'files'
		| 'chats'
		| 'knowledge'
		| 'memory'
		| 'notes'
		| 'tasks'
		| 'code'
		| 'web'
		| 'agent'
		| 'android';
	requiresAndroid?: boolean;
	requiresWorkspace?: boolean;
	policy?: NativeAgentToolPolicy;
};

export type LocalToolContext = {
	includeWebSearch?: boolean;
	includeAndroidTools?: boolean;
	includeLocationTool?: boolean;
	agentWorkspaceId?: string | null;
};

export type LocalToolExecutionContext = {
	agentWorkspaceId?: string | null;
	requestId?: string;
};

const jsonString = (value: unknown) => JSON.stringify(value);

const getErrorMessage = (error: unknown) =>
	error instanceof Error ? error.message : typeof error === 'string' ? error : String(error);

const clampInteger = (value: unknown, defaultValue: number, min: number, max: number) => {
	const numeric = Number(value);
	if (!Number.isFinite(numeric)) {
		return defaultValue;
	}

	return Math.max(min, Math.min(max, Math.trunc(numeric)));
};

const requiredString = (args: JsonRecord, key: string) => {
	const value = args?.[key];
	if (typeof value !== 'string' || !value.trim()) {
		throw new Error(`Missing required string argument "${key}".`);
	}

	return value.trim();
};

const optionalStringArray = (value: unknown) =>
	Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];

const normalizedStringArray = (value: unknown) =>
	optionalStringArray(value)
		.map((item) => item.trim())
		.filter(Boolean);

const contentToText = (content: unknown): string => {
	if (typeof content === 'string') {
		return content;
	}

	if (!Array.isArray(content)) {
		return '';
	}

	return content
		.map((part) => {
			if (typeof part === 'string') return part;
			if (part?.type === 'text') return part.text ?? '';
			return '';
		})
		.join('\n')
		.trim();
};

const truncateText = (value: string, maxChars: number) => {
	if (value.length <= maxChars) {
		return { text: value, truncated: false };
	}

	return { text: value.slice(0, maxChars), truncated: true };
};

const getFileContent = (file: LocalFileRecord) => {
	const data = file?.data ?? {};
	if (typeof data.content === 'string') return data.content;
	if (typeof data.text === 'string') return data.text;
	if (typeof data.markdown === 'string') return data.markdown;
	return '';
};

const fileSummary = (file: LocalFileRecord, maxPreviewChars = 240) => {
	const preview = truncateText(getFileContent(file).replace(/\s+/g, ' ').trim(), maxPreviewChars);
	return {
		id: file.id,
		filename: file.filename ?? file.name,
		name: file.name ?? file.filename,
		type: file.type,
		content_type: file.content_type,
		size: file.size,
		status: file.status,
		created_at: file.created_at,
		updated_at: file.updated_at,
		preview: preview.text,
		preview_truncated: preview.truncated
	};
};

const getChatMessages = (chat: LocalChatRecord): JsonRecord[] =>
	(Object.values(chat?.chat?.history?.messages ?? {}) as JsonRecord[]).sort((a, b) => {
		const left = a?.timestamp ?? a?.created_at ?? a?.updated_at ?? 0;
		const right = b?.timestamp ?? b?.created_at ?? b?.updated_at ?? 0;
		return left - right;
	});

const chatMessageSummary = (message: any, maxChars = 120) => {
	const preview = truncateText(
		contentToText(message?.content).replace(/\s+/g, ' ').trim(),
		maxChars
	);
	return {
		id: message?.id,
		role: message?.role,
		timestamp: message?.timestamp,
		content: preview.text,
		truncated: preview.truncated
	};
};

const chatSummary = (chat: LocalChatRecord) => ({
	id: chat.id,
	title: chat.title,
	created_at: chat.created_at,
	updated_at: chat.updated_at,
	pinned: chat.pinned ?? false,
	archived: chat.archived ?? false,
	preview: getChatMessages(chat)
		.slice(0, 4)
		.map((message) => chatMessageSummary(message))
});

class ArithmeticParser {
	private index = 0;

	constructor(private readonly input: string) {}

	parse() {
		const value = this.parseExpression();
		this.skipWhitespace();
		if (this.index !== this.input.length) {
			throw new Error(`Unexpected token "${this.input[this.index]}".`);
		}
		if (!Number.isFinite(value)) {
			throw new Error('Expression result is not finite.');
		}
		return value;
	}

	private parseExpression(): number {
		let value = this.parseTerm();
		while (true) {
			this.skipWhitespace();
			if (this.match('+')) value += this.parseTerm();
			else if (this.match('-')) value -= this.parseTerm();
			else return value;
		}
	}

	private parseTerm(): number {
		let value = this.parsePower();
		while (true) {
			this.skipWhitespace();
			if (this.match('*')) value *= this.parsePower();
			else if (this.match('/')) {
				const divisor = this.parsePower();
				if (divisor === 0) throw new Error('Division by zero.');
				value /= divisor;
			} else return value;
		}
	}

	private parsePower(): number {
		let value = this.parseUnary();
		this.skipWhitespace();
		if (this.match('^')) value = value ** this.parsePower();
		return value;
	}

	private parseUnary(): number {
		this.skipWhitespace();
		if (this.match('+')) return this.parseUnary();
		if (this.match('-')) return -this.parseUnary();
		return this.parsePrimary();
	}

	private parsePrimary(): number {
		this.skipWhitespace();
		if (this.match('(')) {
			const value = this.parseExpression();
			this.skipWhitespace();
			if (!this.match(')')) throw new Error('Missing closing parenthesis.');
			return value;
		}
		return this.parseNumber();
	}

	private parseNumber(): number {
		this.skipWhitespace();
		const start = this.index;
		while (/[0-9.]/.test(this.input[this.index] ?? '')) this.index += 1;
		const raw = this.input.slice(start, this.index);
		if (!raw || raw === '.' || (raw.match(/\./g) ?? []).length > 1) {
			throw new Error('Expected a number.');
		}
		const value = Number(raw);
		if (!Number.isFinite(value)) throw new Error(`Invalid number "${raw}".`);
		return value;
	}

	private skipWhitespace() {
		while (/\s/.test(this.input[this.index] ?? '')) this.index += 1;
	}

	private match(token: string) {
		if (this.input[this.index] !== token) return false;
		this.index += 1;
		return true;
	}
}

class BigIntArithmeticParser {
	private index = 0;

	constructor(private readonly input: string) {}

	parse() {
		const value = this.parseExpression();
		this.skipWhitespace();
		if (this.index !== this.input.length) {
			throw new Error(`Unexpected token "${this.input[this.index]}".`);
		}
		return value;
	}

	private parseExpression(): bigint {
		let value = this.parseTerm();
		for (;;) {
			this.skipWhitespace();
			if (this.match('+')) value += this.parseTerm();
			else if (this.match('-')) value -= this.parseTerm();
			else return value;
		}
	}

	private parseTerm(): bigint {
		let value = this.parsePower();
		for (;;) {
			this.skipWhitespace();
			if (this.match('*')) value *= this.parsePower();
			else return value;
		}
	}

	private parsePower(): bigint {
		let value = this.parseUnary();
		this.skipWhitespace();
		if (this.match('^')) {
			const exponent = this.parsePower();
			if (exponent < 0n) {
				throw new Error('Negative exponents are not supported for exact integer arithmetic.');
			}
			if (exponent > 100000n) {
				throw new Error('Exponent is too large.');
			}
			value = value ** exponent;
		}
		return value;
	}

	private parseUnary(): bigint {
		this.skipWhitespace();
		if (this.match('+')) return this.parseUnary();
		if (this.match('-')) return -this.parseUnary();
		return this.parsePrimary();
	}

	private parsePrimary(): bigint {
		this.skipWhitespace();
		if (this.match('(')) {
			const value = this.parseExpression();
			this.skipWhitespace();
			if (!this.match(')')) throw new Error('Missing closing parenthesis.');
			return value;
		}
		return this.parseNumber();
	}

	private parseNumber(): bigint {
		this.skipWhitespace();
		const start = this.index;
		while (/[0-9]/.test(this.input[this.index] ?? '')) this.index += 1;
		const raw = this.input.slice(start, this.index);
		if (!raw) {
			throw new Error('Expected a number.');
		}
		return BigInt(raw);
	}

	private skipWhitespace() {
		while (/\s/.test(this.input[this.index] ?? '')) this.index += 1;
	}

	private match(token: string) {
		if (this.input[this.index] !== token) return false;
		this.index += 1;
		return true;
	}
}

const normalizeArithmeticExpression = (expression: string) =>
	expression.replaceAll('×', '*').replaceAll('÷', '/');

const formatBigIntResult = (value: bigint) => {
	if (value <= BigInt(Number.MAX_SAFE_INTEGER) && value >= BigInt(Number.MIN_SAFE_INTEGER)) {
		return Number(value);
	}
	return value.toString();
};

const calculateExpression = (expression: string) => {
	const normalizedExpression = normalizeArithmeticExpression(expression);

	if (!/^[0-9+\-*/^().\s]+$/.test(normalizedExpression)) {
		throw new Error('Expression contains unsupported characters.');
	}

	if (/^[0-9+\-*^()\s]+$/.test(normalizedExpression)) {
		return formatBigIntResult(new BigIntArithmeticParser(normalizedExpression).parse());
	}

	return new ArithmeticParser(normalizedExpression).parse();
};

const currentTimestamp = () => {
	const now = new Date();
	return {
		current_timestamp: Math.floor(now.getTime() / 1000),
		current_iso: now.toISOString()
	};
};

const calculateRelativeTimestamp = (args: JsonRecord) => {
	const now = new Date();
	const adjusted = new Date(now.getTime());
	const daysAgo = clampInteger(args.days_ago, 0, 0, 100000);
	const weeksAgo = clampInteger(args.weeks_ago, 0, 0, 100000);
	const monthsAgo = clampInteger(args.months_ago, 0, 0, 100000);
	const yearsAgo = clampInteger(args.years_ago, 0, 0, 100000);

	adjusted.setUTCDate(adjusted.getUTCDate() - daysAgo - weeksAgo * 7);
	if (monthsAgo) adjusted.setUTCMonth(adjusted.getUTCMonth() - monthsAgo);
	if (yearsAgo) adjusted.setUTCFullYear(adjusted.getUTCFullYear() - yearsAgo);

	return {
		current_timestamp: Math.floor(now.getTime() / 1000),
		current_iso: now.toISOString(),
		calculated_timestamp: Math.floor(adjusted.getTime() / 1000),
		calculated_iso: adjusted.toISOString()
	};
};

const objectSchema = (properties: JsonRecord = {}, required: string[] = []) => ({
	type: 'object',
	properties,
	required,
	additionalProperties: false
});

const stringProp = (description: string) => ({ type: 'string', description });
const intProp = (description: string) => ({ type: 'integer', description });
const boolProp = (description: string) => ({ type: 'boolean', description });

export const LOCAL_CORE_TOOL_SPECS: LocalToolDefinition[] = [
	{
		type: 'function',
		category: 'time',
		function: {
			name: 'get_current_timestamp',
			description: 'Get the current Unix timestamp in seconds and current UTC ISO time.',
			parameters: objectSchema()
		}
	},
	{
		type: 'function',
		category: 'time',
		function: {
			name: 'calculate_timestamp',
			description: 'Calculate a Unix timestamp in seconds relative to now.',
			parameters: objectSchema({
				days_ago: intProp('Number of days to subtract from the current time.'),
				weeks_ago: intProp('Number of weeks to subtract from the current time.'),
				months_ago: intProp('Number of months to subtract from the current time.'),
				years_ago: intProp('Number of years to subtract from the current time.')
			})
		}
	},
	{
		type: 'function',
		category: 'math',
		function: {
			name: 'calculate_expression',
			description:
				'Calculate a numeric arithmetic expression. Supports +, -, *, ×, /, ÷, ^ and parentheses. Integer-only expressions use exact arithmetic.',
			parameters: objectSchema(
				{ expression: stringProp('Arithmetic expression, for example "(12 + 8) / 5".') },
				['expression']
			)
		}
	},
	{
		type: 'function',
		category: 'files',
		function: {
			name: 'list_local_files',
			description:
				'List files stored in the local-first on-device file database by file id. This is not the Agent workspace filesystem.',
			parameters: objectSchema({
				limit: intProp('Maximum number of files to return. Defaults to 20.')
			})
		}
	},
	{
		type: 'function',
		category: 'files',
		function: {
			name: 'search_local_files',
			description:
				'Search local-first on-device file database records by names, metadata, and extracted text. This is not the Agent workspace filesystem.',
			parameters: objectSchema(
				{
					query: stringProp('Search text.'),
					limit: intProp('Maximum number of matching files to return. Defaults to 10.')
				},
				['query']
			)
		}
	},
	{
		type: 'function',
		category: 'files',
		function: {
			name: 'view_local_file',
			description:
				'View a local-first file database record by id and extracted text content when available. For Agent workspace files, use agent_file_read with a path instead.',
			parameters: objectSchema(
				{
					id: stringProp('Local file id.'),
					max_chars: intProp('Maximum content characters to include. Defaults to 20000.')
				},
				['id']
			)
		}
	},
	{
		type: 'function',
		category: 'chats',
		function: {
			name: 'search_local_chats',
			description: 'Search local-first chat history by title and message text.',
			parameters: objectSchema(
				{
					query: stringProp('Search text.'),
					limit: intProp('Maximum number of matching chats to return. Defaults to 10.')
				},
				['query']
			)
		}
	},
	{
		type: 'function',
		category: 'chats',
		function: {
			name: 'view_local_chat',
			description: 'View a local-first chat record and its messages.',
			parameters: objectSchema(
				{
					id: stringProp('Local chat id.'),
					max_chars: intProp('Maximum message content characters to include. Defaults to 20000.')
				},
				['id']
			)
		}
	},
	{
		type: 'function',
		category: 'knowledge',
		function: {
			name: 'search_local_knowledge',
			description:
				'Search the on-device lexical knowledge index built from local files, chats, notes, and memories.',
			parameters: objectSchema(
				{
					query: stringProp('Search text.'),
					limit: intProp('Maximum chunks to return. Defaults to 8.'),
					source_types: {
						type: 'array',
						items: { type: 'string', enum: ['file', 'chat', 'note', 'memory'] },
						description: 'Optional source types to search.'
					}
				},
				['query']
			)
		}
	},
	{
		type: 'function',
		category: 'memory',
		function: {
			name: 'remember_local_memory',
			description: 'Save a durable on-device memory for future local-first chats.',
			parameters: objectSchema(
				{
					content: stringProp('Memory content to store.'),
					tags: { type: 'array', items: { type: 'string' }, description: 'Optional tags.' }
				},
				['content']
			)
		}
	},
	{
		type: 'function',
		category: 'memory',
		function: {
			name: 'search_local_memory',
			description: 'Search durable on-device memories.',
			parameters: objectSchema(
				{
					query: stringProp('Search text.'),
					limit: intProp('Maximum memories to return. Defaults to 10.')
				},
				['query']
			)
		}
	},
	{
		type: 'function',
		category: 'memory',
		function: {
			name: 'forget_local_memory',
			description: 'Delete a durable on-device memory by id.',
			parameters: objectSchema({ id: stringProp('Memory id.') }, ['id'])
		}
	},
	{
		type: 'function',
		category: 'notes',
		function: {
			name: 'create_local_note',
			description: 'Create an on-device local note.',
			parameters: objectSchema(
				{
					title: stringProp('Note title.'),
					content: stringProp('Note content.'),
					tags: { type: 'array', items: { type: 'string' }, description: 'Optional tags.' }
				},
				['title', 'content']
			)
		}
	},
	{
		type: 'function',
		category: 'notes',
		function: {
			name: 'search_local_notes',
			description: 'Search on-device local notes.',
			parameters: objectSchema(
				{
					query: stringProp('Search text.'),
					limit: intProp('Maximum notes to return. Defaults to 10.')
				},
				['query']
			)
		}
	},
	{
		type: 'function',
		category: 'tasks',
		function: {
			name: 'create_local_task',
			description: 'Create a task in the WebUI local database. For native App calendar todos and linked reminders, use agent_todo_create when available.',
			parameters: objectSchema(
				{
					title: stringProp('Task title.'),
					description: stringProp('Optional task description.'),
					due_at: intProp('Optional due timestamp in milliseconds.'),
					status: { type: 'string', enum: ['todo', 'in_progress', 'done', 'cancelled'] }
				},
				['title']
			)
		}
	},
	{
		type: 'function',
		category: 'tasks',
		function: {
			name: 'update_local_task',
			description: 'Update a task in the WebUI local database. Native App calendar todos use agent_todo_update; their IDs are separate.',
			parameters: objectSchema(
				{
					id: stringProp('Task id.'),
					title: stringProp('Updated title.'),
					description: stringProp('Updated description.'),
					due_at: intProp('Updated due timestamp in milliseconds.'),
					status: { type: 'string', enum: ['todo', 'in_progress', 'done', 'cancelled'] }
				},
				['id']
			)
		}
	},
	{
		type: 'function',
		category: 'tasks',
		function: {
			name: 'list_local_tasks',
			description: 'List tasks in the WebUI local database. To query native App calendar todos and reminders, use agent_todo_list when available.',
			parameters: objectSchema({
				status: { type: 'string', enum: ['todo', 'in_progress', 'done', 'cancelled'] },
				limit: intProp('Maximum tasks to return. Defaults to 50.')
			})
		}
	},
	{
		type: 'function',
		category: 'code',
		function: {
			name: 'execute_python',
			description:
				'Execute Python in the on-device Pyodide sandbox. No shell or Android filesystem access.',
			parameters: objectSchema(
				{
					code: stringProp('Python code to execute.'),
					file_ids: {
						type: 'array',
						items: { type: 'string' },
						description: 'Optional local file ids.'
					},
					timeout_ms: intProp('Timeout in milliseconds. Defaults to 60000.')
				},
				['code']
			)
		}
	}
];

export const LOCAL_WEB_SEARCH_TOOL_SPECS: LocalToolDefinition[] = [
	{
		type: 'function',
		category: 'web',
		function: {
			name: 'search_web',
			description:
				'Search the public web from this Android device. Use for current events, recent facts, prices, and schedules.',
			parameters: objectSchema(
				{
					query: stringProp('The web search query.'),
					count: intProp('Maximum number of search results to return. Defaults to 5.')
				},
				['query']
			)
		}
	},
	{
		type: 'function',
		category: 'web',
		function: {
			name: 'fetch_url',
			description:
				'Fetch readable text from a specific web page URL using the Android native bridge.',
			parameters: objectSchema({ url: stringProp('The http or https URL to fetch.') }, ['url'])
		}
	}
];

export const LOCAL_ANDROID_TOOL_SPECS: LocalToolDefinition[] = [
	{
		type: 'function',
		category: 'android',
		requiresAndroid: true,
		function: {
			name: 'get_device_context',
			description:
				'Get safe Android device context such as locale, timezone, battery, SDK, and network state.',
			parameters: objectSchema()
		}
	},
	{
		type: 'function',
		category: 'android',
		requiresAndroid: true,
		function: {
			name: 'get_current_location',
			description:
				'Get the current Android device location after user-enabled location sharing and runtime permission. Use when the user asks for their current location or nearby context.',
			parameters: objectSchema({
				timeout_ms: intProp('Location lookup timeout in milliseconds. Defaults to 15000.'),
				high_accuracy: boolProp('Whether to request high-accuracy GPS location. Defaults to false.')
			})
		}
	},
	{
		type: 'function',
		category: 'android',
		requiresAndroid: true,
		function: {
			name: 'pick_media',
			description: 'Open Android system file/media picker. Requires visible user interaction.',
			parameters: objectSchema({
				mime_type: stringProp('MIME type filter, for example image/*, video/*, audio/*, or */*.'),
				multiple: boolProp('Whether multiple items may be selected.')
			})
		}
	},
	{
		type: 'function',
		category: 'android',
		requiresAndroid: true,
		function: {
			name: 'capture_photo',
			description: 'Open Android camera capture UI. Requires visible user interaction.',
			parameters: objectSchema({ title: stringProp('Optional capture title.') })
		}
	},
	{
		type: 'function',
		category: 'android',
		requiresAndroid: true,
		function: {
			name: 'start_audio_recording',
			description:
				'Start microphone recording on Android after user confirmation and runtime permission.',
			parameters: objectSchema({ request_id: stringProp('Client-generated recording id.') }, [
				'request_id'
			])
		}
	},
	{
		type: 'function',
		category: 'android',
		requiresAndroid: true,
		function: {
			name: 'stop_audio_recording',
			description: 'Stop a previously started Android audio recording.',
			parameters: objectSchema({ request_id: stringProp('Recording id.') }, ['request_id'])
		}
	},
	{
		type: 'function',
		category: 'android',
		requiresAndroid: true,
		function: {
			name: 'list_calendar_events',
			description: 'Read Android calendar events after user confirmation and runtime permission.',
			parameters: objectSchema({
				start_ms: intProp('Start time in milliseconds since epoch.'),
				end_ms: intProp('End time in milliseconds since epoch.'),
				limit: intProp('Maximum events to return. Defaults to 50.')
			})
		}
	},
	{
		type: 'function',
		category: 'android',
		requiresAndroid: true,
		function: {
			name: 'create_calendar_event',
			description:
				'Create an Android calendar event after user confirmation and runtime permission.',
			parameters: objectSchema(
				{
					title: stringProp('Event title.'),
					description: stringProp('Optional event description.'),
					location: stringProp('Optional event location.'),
					start_ms: intProp('Start time in milliseconds since epoch.'),
					end_ms: intProp('End time in milliseconds since epoch.'),
					all_day: boolProp('Whether this is an all-day event.')
				},
				['title', 'start_ms', 'end_ms']
			)
		}
	},
	{
		type: 'function',
		category: 'android',
		requiresAndroid: true,
		function: {
			name: 'update_calendar_event',
			description:
				'Update an Android calendar event after user confirmation and runtime permission.',
			parameters: objectSchema({ id: stringProp('Calendar event id.') }, ['id'])
		}
	},
	{
		type: 'function',
		category: 'android',
		requiresAndroid: true,
		function: {
			name: 'delete_calendar_event',
			description:
				'Delete an Android calendar event after user confirmation and runtime permission.',
			parameters: objectSchema({ id: stringProp('Calendar event id.') }, ['id'])
		}
	},
	{
		type: 'function',
		category: 'android',
		requiresAndroid: true,
		function: {
			name: 'schedule_notification',
			description: 'Schedule a local Android notification/reminder after user confirmation.',
			parameters: objectSchema(
				{
					id: stringProp('Notification id.'),
					title: stringProp('Notification title.'),
					body: stringProp('Notification body.'),
					trigger_at_ms: intProp('Trigger time in milliseconds since epoch.')
				},
				['id', 'title', 'body']
			)
		}
	},
	{
		type: 'function',
		category: 'android',
		requiresAndroid: true,
		function: {
			name: 'cancel_notification',
			description: 'Cancel a scheduled local Android notification.',
			parameters: objectSchema({ id: stringProp('Notification id.') }, ['id'])
		}
	}
];

export const getAvailableLocalTools = async (context: LocalToolContext = {}) => {
	const nativeAgentTools =
		supportsNativeAgentTools() ? await listNativeAgentTools().catch(() => []) : [];
	const availableNativeAgentTools = nativeAgentTools.filter(
		(tool) => context.agentWorkspaceId || tool.requiresWorkspace === false
	);

	return [
		...LOCAL_CORE_TOOL_SPECS,
		...(context.includeWebSearch ? LOCAL_WEB_SEARCH_TOOL_SPECS : []),
		...(context.includeAndroidTools && supportsNativeAndroidTools()
			? LOCAL_ANDROID_TOOL_SPECS.filter(
					(tool) => context.includeLocationTool || tool.function.name !== 'get_current_location'
				)
			: []),
		...availableNativeAgentTools.map((tool) => ({
			...tool,
			category: 'agent' as const,
			requiresAndroid: true,
			requiresWorkspace: tool.requiresWorkspace ?? true
		}))
	];
};

const executeAndroidTool = async (name: string, args: JsonRecord) => {
	if (name === 'get_device_context') return getDeviceContext();
	if (name === 'get_current_location') {
		return getCurrentLocation({
			timeoutMs: clampInteger(args.timeout_ms, 15000, 1000, 60000),
			highAccuracy: args.high_accuracy === true
		});
	}
	if (name === 'pick_media')
		return pickMedia({ mimeType: args.mime_type, multiple: args.multiple });
	if (name === 'capture_photo') return capturePhoto({ title: args.title });
	if (name === 'start_audio_recording')
		return startAudioRecording({ requestId: requiredString(args, 'request_id') });
	if (name === 'stop_audio_recording')
		return stopAudioRecording({ requestId: requiredString(args, 'request_id') });
	if (name === 'list_calendar_events') {
		return listCalendarEvents({
			startMs: Number(args.start_ms) || undefined,
			endMs: Number(args.end_ms) || undefined,
			limit: clampInteger(args.limit, 50, 1, 200)
		});
	}
	if (name === 'create_calendar_event') {
		return createCalendarEvent({
			title: requiredString(args, 'title'),
			description: args.description ?? '',
			location: args.location ?? '',
			startMs: Number(args.start_ms),
			endMs: Number(args.end_ms),
			allDay: args.all_day === true
		});
	}
	if (name === 'update_calendar_event') {
		return updateCalendarEvent({
			id: requiredString(args, 'id'),
			...args,
			startMs: args.start_ms,
			endMs: args.end_ms,
			allDay: args.all_day
		});
	}
	if (name === 'delete_calendar_event')
		return deleteCalendarEvent({ id: requiredString(args, 'id') });
	if (name === 'schedule_notification') {
		return scheduleNotification({
			id: requiredString(args, 'id'),
			title: requiredString(args, 'title'),
			body: requiredString(args, 'body'),
			triggerAtMs: Number(args.trigger_at_ms) || Date.now()
		});
	}
	if (name === 'cancel_notification') return cancelNotification({ id: requiredString(args, 'id') });
	throw new Error(`Unknown Android local tool "${name}".`);
};

const confirmMailSendIfNeeded = async (name: string, args: JsonRecord) => {
	if (name !== 'agent_mail_send') return;
	await confirmNativeMailSend({
		to: normalizedStringArray(args.to),
		cc: normalizedStringArray(args.cc),
		bcc: normalizedStringArray(args.bcc),
		subject: typeof args.subject === 'string' ? args.subject : '',
		body: typeof args.body === 'string' ? args.body : '',
		isHtml: args.is_html === true,
		attachmentCount: Array.isArray(args.attachments) ? args.attachments.length : 0
	});
};

export const executeRegisteredLocalTool = async (
	name: string,
	args: JsonRecord = {},
	context: LocalToolExecutionContext = {}
) => {
	try {
		if (name.startsWith('agent_')) {
			if (!supportsNativeAgentTools()) throw new Error('Native Agent tools are unavailable.');
			const tool = (await listNativeAgentTools()).find((item) => item.function.name === name);
			if (!tool) throw new Error(`Native Agent tool "${name}" is not registered.`);
			if (!context.agentWorkspaceId && tool.requiresWorkspace !== false) {
				throw new Error('Native Agent workspace is not available for this chat.');
			}
			await confirmMailSendIfNeeded(name, args);

			return jsonString(
				await executeNativeAgentTool({
					...(context.requestId ? { requestId: context.requestId } : {}),
					workspaceId: context.agentWorkspaceId ?? '',
					toolName: name,
					arguments: args
				})
			);
		}

		if (name === 'get_current_timestamp') {
			return jsonString(currentTimestamp());
		}

		if (name === 'calculate_timestamp') {
			return jsonString(calculateRelativeTimestamp(args));
		}

		if (name === 'calculate_expression') {
			const expression = requiredString(args, 'expression');
			return jsonString({ expression, result: calculateExpression(expression) });
		}

		if (name === 'list_local_files') {
			const limit = clampInteger(args.limit, 20, 1, 100);
			const files = await listLocalFiles();
			return jsonString({ files: files.slice(0, limit).map((file) => fileSummary(file)) });
		}

		if (name === 'search_local_files') {
			const query = requiredString(args, 'query').toLowerCase();
			const limit = clampInteger(args.limit, 10, 1, 100);
			const files = await listLocalFiles();
			const matches = files.filter((file) => {
				const haystack = [
					file.filename,
					file.name,
					file.type,
					file.content_type,
					JSON.stringify(file.metadata ?? {}),
					getFileContent(file)
				]
					.join('\n')
					.toLowerCase();
				return haystack.includes(query);
			});
			return jsonString({ query, files: matches.slice(0, limit).map((file) => fileSummary(file)) });
		}

		if (name === 'view_local_file') {
			const id = requiredString(args, 'id');
			const maxChars = clampInteger(args.max_chars, 20000, 1, 200000);
			const file = await getLocalFileRecord(id);
			if (!file) throw new Error(`Local file "${id}" was not found.`);
			const content = truncateText(getFileContent(file), maxChars);
			return jsonString({
				...fileSummary(file),
				url: file.url ?? null,
				metadata: file.metadata ?? null,
				content: content.text,
				content_truncated: content.truncated
			});
		}

		if (name === 'search_local_chats') {
			const query = requiredString(args, 'query');
			const limit = clampInteger(args.limit, 10, 1, 100);
			const chats = await listLocalChats({ page: null, archived: false, search: query });
			return jsonString({ query, chats: chats.slice(0, limit).map((chat) => chatSummary(chat)) });
		}

		if (name === 'view_local_chat') {
			const id = requiredString(args, 'id');
			const maxChars = clampInteger(args.max_chars, 20000, 1, 200000);
			const chat = await getLocalChat(id);
			if (!chat) throw new Error(`Local chat "${id}" was not found.`);

			let remainingChars = maxChars;
			const messages = getChatMessages(chat).map((message) => {
				const text = contentToText(message?.content);
				const truncated = truncateText(text, Math.max(0, remainingChars));
				remainingChars -= truncated.text.length;
				return {
					id: message?.id,
					role: message?.role,
					timestamp: message?.timestamp,
					content: truncated.text,
					truncated: truncated.truncated || remainingChars <= 0
				};
			});

			return jsonString({ ...chatSummary(chat), messages });
		}

		if (name === 'search_local_knowledge') {
			const query = requiredString(args, 'query');
			const limit = clampInteger(args.limit, 8, 1, 50);
			const results = await searchLocalKnowledge({
				query,
				limit,
				sourceTypes: optionalStringArray(args.source_types) as any
			});
			return jsonString({ query, results });
		}

		if (name === 'remember_local_memory') {
			return jsonString(
				await createLocalMemory({
					content: requiredString(args, 'content'),
					tags: optionalStringArray(args.tags)
				})
			);
		}

		if (name === 'search_local_memory') {
			const query = requiredString(args, 'query');
			const limit = clampInteger(args.limit, 10, 1, 50);
			return jsonString({ query, memories: await searchLocalMemories({ query, limit }) });
		}

		if (name === 'forget_local_memory') {
			return jsonString({
				id: requiredString(args, 'id'),
				deleted: await deleteLocalMemory(requiredString(args, 'id'))
			});
		}

		if (name === 'create_local_note') {
			return jsonString(
				await createLocalNote({
					title: requiredString(args, 'title'),
					content: requiredString(args, 'content'),
					tags: optionalStringArray(args.tags)
				})
			);
		}

		if (name === 'search_local_notes') {
			const query = requiredString(args, 'query');
			const limit = clampInteger(args.limit, 10, 1, 50);
			return jsonString({ query, notes: await searchLocalNotes({ query, limit }) });
		}

		if (name === 'create_local_task') {
			return jsonString(
				await createLocalTask({
					title: requiredString(args, 'title'),
					description: typeof args.description === 'string' ? args.description : '',
					status: (args.status as LocalTaskRecord['status']) ?? 'todo',
					due_at: Number.isFinite(Number(args.due_at)) ? Number(args.due_at) : null
				})
			);
		}

		if (name === 'update_local_task') {
			const id = requiredString(args, 'id');
			const updates: Partial<LocalTaskRecord> = {};
			if (typeof args.title === 'string') updates.title = args.title;
			if (typeof args.description === 'string') updates.description = args.description;
			if (typeof args.status === 'string')
				updates.status = args.status as LocalTaskRecord['status'];
			if (Number.isFinite(Number(args.due_at))) updates.due_at = Number(args.due_at);
			const task = await updateLocalTask(id, updates);
			if (!task) throw new Error(`Local task "${id}" was not found.`);
			return jsonString(task);
		}

		if (name === 'list_local_tasks') {
			return jsonString({
				tasks: await listLocalTasks({
					status:
						typeof args.status === 'string'
							? (args.status as LocalTaskRecord['status'])
							: undefined,
					limit: clampInteger(args.limit, 50, 1, 200)
				})
			});
		}

		if (name === 'execute_python') {
			const { executePythonWithWorker } = await import('../code-interpreter');
			const fileIds = optionalStringArray(args.file_ids);
			return jsonString(
				await executePythonWithWorker({
					code: requiredString(args, 'code'),
					files: fileIds.map((id) => ({ id })),
					timeoutMs: clampInteger(args.timeout_ms, 60000, 1000, 300000)
				})
			);
		}

		if (LOCAL_ANDROID_TOOL_SPECS.some((tool) => tool.function.name === name)) {
			return jsonString(await executeAndroidTool(name, args));
		}

		throw new Error(`Unknown local tool "${name}".`);
	} catch (error) {
		return jsonString({ error: getErrorMessage(error), tool: name });
	}
};
