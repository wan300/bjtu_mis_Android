import { getLocalSettings } from './db';
import {
	createWebSearchSources,
	fetchUrl,
	getWebSearchStatus,
	searchWeb,
	supportsNativeWebSearch,
	type LocalWebSearchResult
} from './web-search';
import { normalizeLocalWebSearchSettings, type LocalWebSearchSettings } from './web-search-config';
import {
	executeRegisteredLocalTool,
	getAvailableLocalTools,
	LOCAL_CORE_TOOL_SPECS,
	LOCAL_WEB_SEARCH_TOOL_SPECS,
	type LocalToolDefinition
} from './tools/registry';
import {
	beginNativeAgentKeepAlive,
	endNativeAgentKeepAlive,
	supportsNativeAgentTools
} from './native-agent-tools';

type JsonRecord = Record<string, any>;

type LocalToolCall = {
	id: string;
	type: 'function';
	function: {
		name: string;
		arguments: string | JsonRecord;
	};
};

type ProviderTurn = {
	content: string;
	toolCalls: LocalToolCall[];
	reasoning?: string;
	reasoningDone?: boolean;
	reasoningDuration?: number;
	usage?: JsonRecord;
	raw?: JsonRecord;
	sources?: JsonRecord[];
	statuses?: JsonRecord[];
	streamContent?: string;
};

type AgentTurnDecision =
	| { kind: 'tool_calls'; turn: ProviderTurn }
	| { kind: 'final'; turn: ProviderTurn }
	| { kind: 'invalid'; turn: ProviderTurn; reason: string };

type FinalAnswerReviewResult = {
	approved: boolean;
	reason: string;
};

type LocalToolMessage = {
	role: 'tool';
	tool_call_id: string;
	name: string;
	content: string;
};

type LocalDisplayOutputItem =
	| {
			type: 'message';
			content: string;
	  }
	| {
			type: 'function_call';
			id: string;
			call_id: string;
			name: string;
			arguments: string;
			status: 'in_progress' | 'completed';
	  }
	| {
			type: 'function_call_output';
			id: string;
			call_id: string;
			output: { type: 'input_text'; text: string }[];
			status: 'completed';
	  };

export type LocalProviderRequest = (
	providerBody: JsonRecord
) => Promise<[Response | null, AbortController]>;

export const DEFAULT_LOCAL_AGENT_MAX_TOOL_CALL_RETRIES = 30;
export const DEFAULT_LOCAL_WEB_SEARCH_MAX_MODEL_ROUNDS = 4;
export const DEFAULT_LOCAL_WEB_SEARCH_MAX_TOOL_CALLS = 6;
const LOCAL_AGENT_KEEP_ALIVE_REASON = 'agent';

const createLocalAgentKeepAliveToken = () => {
	const randomId =
		typeof globalThis.crypto?.randomUUID === 'function'
			? globalThis.crypto.randomUUID()
			: Math.random().toString(36).slice(2);
	return `agent-${Date.now()}-${randomId}`;
};

const startLocalAgentKeepAlive = async (token: string) => {
	if (!supportsNativeAgentTools()) {
		return false;
	}

	try {
		return await beginNativeAgentKeepAlive({ token, reason: LOCAL_AGENT_KEEP_ALIVE_REASON });
	} catch (error) {
		console.warn('Failed to start native Agent keep-alive:', error);
		return false;
	}
};

const stopLocalAgentKeepAlive = async (token: string) => {
	if (!supportsNativeAgentTools()) {
		return;
	}

	try {
		await endNativeAgentKeepAlive({ token });
	} catch (error) {
		console.warn('Failed to stop native Agent keep-alive:', error);
	}
};

export const getLocalAgentMaxToolCallRetries = (env: JsonRecord = import.meta.env) => {
	const parsed = Number(env?.VITE_LOCAL_AGENT_MAX_TOOL_CALL_RETRIES);
	if (!Number.isFinite(parsed)) {
		return DEFAULT_LOCAL_AGENT_MAX_TOOL_CALL_RETRIES;
	}

	return Math.max(0, Math.trunc(parsed));
};

export const shouldUseLocalAgentLoop = (body: JsonRecord) =>
	supportsNativeAgentTools() ||
	body?.params?.function_calling === 'native' ||
	body?.features?.android_device_tools === true ||
	shouldUseLocalCodeInterpreterAgentLoop(body) ||
	(typeof body?.params?.agent_workspace_id === 'string' && body.params.agent_workspace_id.trim() !== '') ||
	shouldUseLocalWebSearchAgentLoop(body);

export const shouldUseLocalWebSearchAgentLoop = (body: JsonRecord) =>
	body?.features?.web_search === true && supportsNativeWebSearch();

export const shouldUseLocalCodeInterpreterAgentLoop = (body: JsonRecord) =>
	body?.features?.code_interpreter === true;

export const LOCAL_AGENT_LIMITATIONS = [
	'No MCP tools.',
	'No terminal tools.',
	'No server-side Python tools.',
	'No server-side RAG.',
	'No backend permission checks.',
	'Provider and model must support OpenAI-compatible native tool calling.'
];

const toProviderTool = (tool: LocalToolDefinition) => ({
	type: tool.type,
	function: tool.function
});

export const LOCAL_AGENT_TOOLS = LOCAL_CORE_TOOL_SPECS.map(toProviderTool);
export const LOCAL_WEB_SEARCH_TOOLS = LOCAL_WEB_SEARCH_TOOL_SPECS.map(toProviderTool);

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

type LocalAgentRunContext = {
	toolParameters: Map<string, JsonRecord>;
	webSearchEnabled: boolean;
	webSearchSettings: LocalWebSearchSettings;
	sources: JsonRecord[];
	statuses: JsonRecord[];
	displayOutput: LocalDisplayOutputItem[];
	emitContentSnapshot?: (content: string) => void;
	toolCallCount: number;
	forceRagFallback: boolean;
	webSearchRetryReported: boolean;
	webSearchUnavailable: boolean;
	agentWorkspaceId?: string | null;
};

const createStatus = (description: string, extra: JsonRecord = {}) => ({
	action: 'web_search',
	description,
	done: true,
	...extra
});

const escapeHtml = (value: unknown) =>
	String(value ?? '').replace(/[&<>"']/g, (char) => {
		if (char === '&') return '&amp;';
		if (char === '<') return '&lt;';
		if (char === '>') return '&gt;';
		if (char === '"') return '&quot;';
		return '&#x27;';
	});

const getReasoningContent = (value: JsonRecord | null | undefined) =>
	contentToText(value?.reasoning_content ?? value?.reasoning ?? value?.thinking);

const renderReasoningDetails = (reasoning: string, done = true, duration = 0) => {
	const quoted = reasoning
		.trim()
		.split(/\r?\n/)
		.map((line) => (line.startsWith('>') ? line : `> ${line}`))
		.join('\n');
	const safeDuration = Math.max(0, Math.trunc(duration));
	const durationAttribute = done ? ` duration="${safeDuration}"` : '';

	return `<details type="reasoning" done="${done ? 'true' : 'false'}"${durationAttribute}>\n<summary>${
		done ? `Thought for ${safeDuration} seconds` : 'Thinking...'
	}</summary>\n${escapeHtml(quoted)}\n</details>`;
};

const convertReasoningTagsToDetails = (content = '') => {
	let converted = content;

	for (const tag of ['think', 'thinking', 'reasoning']) {
		const tagRegex = new RegExp(`<${tag}(?:\\s[^>]*)?>([\\s\\S]*?)<\\/${tag}>`, 'gi');
		converted = converted.replace(tagRegex, (_match: string, reasoning: string) =>
			renderReasoningDetails(reasoning ?? '', true, 0)
		);
	}

	return converted;
};

const formatTurnContent = (turn: ProviderTurn, options: { final?: boolean } = {}) => {
	const parts: string[] = [];
	const reasoning = turn.reasoning?.trim();
	if (reasoning) {
		const hasAnswerOrToolDelta = Boolean((turn.content ?? '').trim() || turn.toolCalls.length);
		const reasoningDone =
			options.final === true ? true : (turn.reasoningDone ?? hasAnswerOrToolDelta);
		parts.push(
			renderReasoningDetails(
				reasoning,
				reasoningDone,
				reasoningDone ? (turn.reasoningDuration ?? 0) : 0
			)
		);
	}

	const content = convertReasoningTagsToDetails(turn.content ?? '').trim();
	if (content) {
		parts.push(content);
	}

	if (options.final === false) {
		for (const toolCall of turn.toolCalls) {
			parts.push(
				renderLocalToolCallDetails({
					callId: toolCall.id,
					name: toolCall.function.name,
					argumentsText: stringifyToolArguments(toolCall.function.arguments),
					done: false
				})
			);
		}
	}

	return parts.join('\n\n').trim();
};

const outputId = (() => {
	let index = 0;
	return (prefix: string) => `local-${prefix}-${++index}`;
})();

const renderLocalToolCallDetails = ({
	callId,
	name,
	argumentsText,
	done,
	resultText
}: {
	callId: string;
	name: string;
	argumentsText: string;
	done: boolean;
	resultText?: string;
}) => {
	const escapedCallId = escapeHtml(callId);
	const escapedName = escapeHtml(name);
	const escapedArguments = escapeHtml(JSON.stringify(argumentsText ?? ''));

	if (!done) {
		return `<details type="tool_calls" done="false" id="${escapedCallId}" name="${escapedName}" arguments="${escapedArguments}">\n<summary>Executing...</summary>\n</details>`;
	}

	return `<details type="tool_calls" done="true" id="${escapedCallId}" name="${escapedName}" arguments="${escapedArguments}">\n<summary>Tool Executed</summary>\n${escapeHtml(
		JSON.stringify(resultText ?? '')
	)}\n</details>`;
};

const serializeLocalDisplayOutput = (output: LocalDisplayOutputItem[], finalContent = '') => {
	const parts: string[] = [];
	const toolOutputs = new Map<
		string,
		Extract<LocalDisplayOutputItem, { type: 'function_call_output' }>
	>();

	for (const item of output) {
		if (item.type === 'function_call_output') {
			toolOutputs.set(item.call_id, item);
		}
	}

	for (const item of output) {
		if (item.type === 'message') {
			const text = item.content.trim();
			if (text) {
				parts.push(text);
			}
			continue;
		}

		if (item.type !== 'function_call') {
			continue;
		}

		const resultItem = toolOutputs.get(item.call_id);

		if (resultItem) {
			const resultText = resultItem.output
				.filter((part) => part.type === 'input_text')
				.map((part) => part.text)
				.join('');
			parts.push(
				renderLocalToolCallDetails({
					callId: item.call_id,
					name: item.name,
					argumentsText: item.arguments ?? '',
					done: true,
					resultText
				})
			);
		} else {
			parts.push(
				renderLocalToolCallDetails({
					callId: item.call_id,
					name: item.name,
					argumentsText: item.arguments ?? '',
					done: false
				})
			);
		}
	}

	const finalText = finalContent.trim();
	if (finalText) {
		parts.push(finalText);
	}

	return parts.join('\n').trim();
};

const emitLocalDisplaySnapshot = (context: LocalAgentRunContext, finalContent = '') => {
	if (!context.emitContentSnapshot) {
		return;
	}

	const content = serializeLocalDisplayOutput(context.displayOutput, finalContent);
	if (content) {
		context.emitContentSnapshot(content);
	}
};

const finalizeLocalAgentTurn = (
	turn: ProviderTurn,
	context: LocalAgentRunContext
): ProviderTurn => {
	const content = formatTurnContent(turn);

	if (context.displayOutput.length === 0) {
		return {
			...turn,
			content,
			streamContent: content
		};
	}

	return {
		...turn,
		content: serializeLocalDisplayOutput(context.displayOutput, content),
		streamContent: content ? `\n${content}` : ''
	};
};

const uniqueSources = (sources: JsonRecord[]) => {
	const seen = new Set<string>();
	return sources.filter((source) => {
		const id = source?.source?.id ?? source?.metadata?.[0]?.source ?? JSON.stringify(source);
		if (seen.has(id)) {
			return false;
		}
		seen.add(id);
		return true;
	});
};

const createSearchContext = (results: LocalWebSearchResult[]) =>
	results
		.map((result, index) => {
			const parts = [
				`[${index + 1}] ${result.title || result.url}`,
				result.url,
				result.snippet ? `Snippet: ${result.snippet}` : '',
				result.content ? `Content excerpt: ${result.content}` : '',
				result.error ? `Fetch error: ${result.error}` : ''
			].filter(Boolean);

			return parts.join('\n');
		})
		.join('\n\n');

const getLastUserQuery = (messages: JsonRecord[] = []) => {
	const userMessage = [...messages].reverse().find((message) => message?.role === 'user');
	return contentToText(userMessage?.content).replace(/\s+/g, ' ').trim();
};

export const executeLocalTool = executeRegisteredLocalTool;

const isPlainJsonRecord = (value: unknown): value is JsonRecord => {
	if (typeof value !== 'object' || value === null || Array.isArray(value)) {
		return false;
	}
	const prototype = Object.getPrototypeOf(value);
	return prototype === Object.prototype || prototype === null;
};

const stringifyToolArguments = (value: unknown) => {
	if (typeof value === 'string') {
		return value;
	}
	if (isPlainJsonRecord(value)) {
		return jsonString(value);
	}
	return '';
};

const copyStringAlias = (args: JsonRecord, target: string, aliases: string[]) => {
	let copied = false;
	if (typeof args[target] === 'string' && args[target].trim()) {
		copied = true;
	} else {
		for (const alias of aliases) {
			if (typeof args[alias] === 'string' && args[alias].trim()) {
				args[target] = args[alias];
				copied = true;
				break;
			}
		}
	}

	if (copied) {
		aliases.forEach((alias) => {
			delete args[alias];
		});
	}
};

const copyIntegerAlias = (args: JsonRecord, target: string, aliases: string[]) => {
	let copied = false;
	if (Number.isFinite(Number(args[target]))) {
		copied = true;
	} else {
		for (const alias of aliases) {
			const value = args[alias];
			if (Number.isFinite(Number(value))) {
				args[target] = Number(value);
				copied = true;
				break;
			}
		}
	}

	if (copied) {
		aliases.forEach((alias) => {
			delete args[alias];
		});
	}
};

const normalizeAgentToolArguments = (toolName: string, args: JsonRecord) => {
	if (!toolName.startsWith('agent_')) {
		return args;
	}

	const normalized = { ...args };
	const fileAliases = ['filename', 'file'];

	if (
		toolName === 'agent_file_list' ||
		toolName === 'agent_file_read' ||
		toolName === 'agent_file_write' ||
		toolName === 'agent_file_delete' ||
		toolName === 'agent_document_extract_pdf' ||
		toolName === 'agent_document_extract_docx'
	) {
		copyStringAlias(normalized, 'path', fileAliases);
	}

	if (toolName === 'agent_archive_extract') {
		copyStringAlias(normalized, 'archivePath', ['archive_path', 'path', ...fileAliases]);
		copyStringAlias(normalized, 'targetDir', ['target_dir']);
	}

	if (toolName === 'agent_archive_create_zip') {
		copyStringAlias(normalized, 'sourceDir', ['source_dir', 'path']);
		copyStringAlias(normalized, 'zipPath', ['zip_path', 'output_path', 'outputPath']);
	}

	if (
		toolName === 'agent_document_extract_pdf' ||
		toolName === 'agent_document_extract_docx' ||
		toolName === 'agent_document_generate_pdf' ||
		toolName === 'agent_document_generate_docx'
	) {
		copyStringAlias(normalized, 'outputPath', ['output_path']);
	}

	if (toolName === 'agent_document_generate_pdf' || toolName === 'agent_document_generate_docx') {
		copyStringAlias(normalized, 'contentMarkdown', ['content_markdown', 'markdown', 'content']);
	}

	if (toolName === 'agent_run_javascript') {
		copyIntegerAlias(normalized, 'timeoutSeconds', ['timeout_seconds']);
		if (!Number.isFinite(Number(normalized.timeoutSeconds)) && Number.isFinite(Number(normalized.timeout_ms))) {
			normalized.timeoutSeconds = Math.max(1, Math.ceil(Number(normalized.timeout_ms) / 1000));
		}
		delete normalized.timeout_ms;
	}

	if (toolName === 'agent_package_results') {
		copyStringAlias(normalized, 'finalAnswer', ['final_answer', 'answer', 'content']);
	}

	return normalized;
};

const parseToolArguments = (toolCall: LocalToolCall) => {
	const raw = toolCall.function.arguments;
	if (!raw) {
		return {};
	}

	if (isPlainJsonRecord(raw)) {
		return normalizeAgentToolArguments(toolCall.function.name, raw);
	}

	if (typeof raw !== 'string') {
		throw new Error('Tool call arguments must be a JSON object or a JSON object string.');
	}

	const trimmed = raw.trim();
	if (!trimmed) {
		return {};
	}

	try {
		const parsed = JSON.parse(trimmed);
		if (!isPlainJsonRecord(parsed)) {
			throw new Error('Tool arguments must be a JSON object.');
		}
		return normalizeAgentToolArguments(toolCall.function.name, parsed);
	} catch (error) {
		throw new Error(`Tool call arguments are invalid JSON: ${getErrorMessage(error)}`);
	}
};

const decodeXmlText = (value: string) =>
	value.replace(/&(#x[0-9a-f]+|#\d+|amp|lt|gt|quot|apos);/gi, (match, entity: string) => {
		const normalized = entity.toLowerCase();
		if (normalized === 'amp') return '&';
		if (normalized === 'lt') return '<';
		if (normalized === 'gt') return '>';
		if (normalized === 'quot') return '"';
		if (normalized === 'apos') return "'";
		if (normalized.startsWith('#x')) {
			const codePoint = Number.parseInt(normalized.slice(2), 16);
			return Number.isInteger(codePoint) && codePoint >= 0 && codePoint <= 0x10ffff
				? String.fromCodePoint(codePoint)
				: match;
		}
		if (normalized.startsWith('#')) {
			const codePoint = Number.parseInt(normalized.slice(1), 10);
			return Number.isInteger(codePoint) && codePoint >= 0 && codePoint <= 0x10ffff
				? String.fromCodePoint(codePoint)
				: match;
		}
		return match;
	});

const normalizeInlineToolArguments = (toolName: string, args: JsonRecord) => {
	return normalizeAgentToolArguments(toolName, args);
};

const AGENT_TOOL_ARGUMENT_EXAMPLES: Record<string, JsonRecord> = {
	agent_file_list: { path: 'inbox' },
	agent_file_read: { path: 'inbox/example.txt' },
	agent_file_write: { path: 'work/notes.md', content: 'Text to write.' },
	agent_file_delete: { path: 'work/notes.md' },
	agent_archive_extract: {
		archivePath: 'inbox/homework.zip',
		targetDir: 'work/attachments/homework'
	},
	agent_archive_create_zip: { sourceDir: 'output', zipPath: 'output/results.zip' },
	agent_document_extract_pdf: { path: 'inbox/task.pdf', outputPath: 'work/task.md' },
	agent_document_extract_docx: { path: 'inbox/task.docx', outputPath: 'work/task.md' },
	agent_document_generate_pdf: {
		title: 'Answer',
		contentMarkdown: '# Answer\n\n...',
		outputPath: 'output/answer.pdf'
	},
	agent_document_generate_docx: {
		title: 'Answer',
		contentMarkdown: '# Answer\n\n...',
		outputPath: 'output/answer.docx'
	},
	agent_run_javascript: { code: 'return input.value + 1;', input: { value: 1 } },
	agent_package_results: { finalAnswer: 'Final answer markdown.' }
};

const missingToolArguments = (parameters: JsonRecord | undefined, args: JsonRecord): string[] => {
	const required: unknown[] = Array.isArray(parameters?.required) ? parameters.required : [];
	return required.filter((key): key is string => {
		if (typeof key !== 'string') return false;
		const value = args[key];
		return typeof value === 'string' ? !value.trim() : value === undefined || value === null;
	});
};

const buildToolArgumentError = (toolName: string, error: unknown, missing: string[] = []) => {
	const example = AGENT_TOOL_ARGUMENT_EXAMPLES[toolName];
	const output: JsonRecord = {
		error: getErrorMessage(error),
		tool: toolName
	};
	if (missing.length > 0) {
		output.missing = missing;
	}
	if (example) {
		output.expected_arguments_example = example;
	}
	return output;
};

const parseInlineXmlToolArguments = (toolName: string, body: string): JsonRecord => {
	const trimmed = body.trim();
	if (!trimmed) {
		return {};
	}

	if (trimmed.startsWith('{')) {
		const parsed = JSON.parse(trimmed);
		if (!isPlainJsonRecord(parsed)) {
			throw new Error('Inline tool arguments must be a JSON object.');
		}
		return normalizeInlineToolArguments(toolName, parsed as JsonRecord);
	}

	const args: JsonRecord = {};
	const fieldRegex = /<([A-Za-z_][\w.-]*)\b[^>]*>([\s\S]*?)<\/\1>/g;
	let match: RegExpExecArray | null;

	while ((match = fieldRegex.exec(body)) !== null) {
		const key = match[1];
		const value = decodeXmlText((match[2] ?? '').trim());
		if (key) {
			args[key] = value;
		}
	}

	return normalizeInlineToolArguments(toolName, args);
};

const extractInlineXmlToolCalls = (
	content: string,
	availableToolNames: Set<string>
): { content: string; toolCalls: LocalToolCall[] } => {
	if (!content.includes('<agent_')) {
		return { content, toolCalls: [] };
	}

	const matches: { start: number; end: number; name: string; rawArguments: string }[] = [];
	const coveredRanges: { start: number; end: number }[] = [];
	const overlapsCoveredRange = (start: number, end: number) =>
		coveredRanges.some((range) => start < range.end && end > range.start);
	const addMatch = (start: number, end: number, name: string, rawArguments = '') => {
		if (!availableToolNames.has(name) || overlapsCoveredRange(start, end)) {
			return;
		}

		matches.push({ start, end, name, rawArguments });
		coveredRanges.push({ start, end });
	};

	const closedToolRegex = /<(agent_[A-Za-z0-9_]+)\b[^>]*>([\s\S]*?)<\/\1>/g;
	let closedMatch: RegExpExecArray | null;
	while ((closedMatch = closedToolRegex.exec(content)) !== null) {
		addMatch(
			closedMatch.index,
			closedMatch.index + closedMatch[0].length,
			closedMatch[1],
			closedMatch[2] ?? ''
		);
	}

	const selfClosingToolRegex = /<(agent_[A-Za-z0-9_]+)\b[^>]*\/>/g;
	let selfClosingMatch: RegExpExecArray | null;
	while ((selfClosingMatch = selfClosingToolRegex.exec(content)) !== null) {
		addMatch(
			selfClosingMatch.index,
			selfClosingMatch.index + selfClosingMatch[0].length,
			selfClosingMatch[1]
		);
	}

	const bareToolRegex = /(^|\r?\n)[ \t]*<(agent_[A-Za-z0-9_]+)\b(?![^>]*\/>)[^>]*>[ \t]*(?=\r?\n|$)/g;
	let bareMatch: RegExpExecArray | null;
	while ((bareMatch = bareToolRegex.exec(content)) !== null) {
		addMatch(bareMatch.index, bareMatch.index + bareMatch[0].length, bareMatch[2]);
	}

	matches.sort((left, right) => left.start - right.start);

	if (matches.length === 0) {
		return { content, toolCalls: [] };
	}

	const toolCalls: LocalToolCall[] = [];
	let strippedContent = '';
	let lastIndex = 0;

	for (const match of matches) {
		let argumentsText: string;
		try {
			argumentsText = jsonString(parseInlineXmlToolArguments(match.name, match.rawArguments));
		} catch {
			argumentsText = match.rawArguments.trim();
		}
		toolCalls.push({
			id: outputId('xml-fc'),
			type: 'function',
			function: {
				name: match.name,
				arguments: argumentsText
			}
		});
		strippedContent += content.slice(lastIndex, match.start);
		lastIndex = match.end;
	}

	strippedContent += content.slice(lastIndex);

	return {
		content: strippedContent.replace(/\n{3,}/g, '\n\n').trim(),
		toolCalls
	};
};

const normalizeInlineXmlToolTurn = (
	turn: ProviderTurn,
	availableToolNames: Set<string>
): ProviderTurn => {
	if (turn.toolCalls.length > 0) {
		return turn;
	}

	const inline = extractInlineXmlToolCalls(turn.content ?? '', availableToolNames);
	if (inline.toolCalls.length === 0) {
		return turn;
	}

	return {
		...turn,
		content: inline.content,
		toolCalls: inline.toolCalls
	};
};

const FINAL_ANSWER_LEAK_PATTERNS: { label: string; regex: RegExp }[] = [
	{ label: 'DSML tool protocol marker', regex: /\bDSML\b/i },
	{ label: 'tool_calls XML marker', regex: /<\s*\/?\s*tool_calls\b|tool_calls\s*>/i },
	{ label: 'tool invocation marker', regex: /\binvoke\s+name\s*=/i },
	{ label: 'tool parameter marker', regex: /\bparameter\s+name\s*=/i },
	{
		label: 'rendered tool call details',
		regex: /<details\s+type\s*=\s*["']tool_calls["']/i
	},
	{ label: 'unparsed native Agent tool tag', regex: /<\s*\/?\s*agent_[A-Za-z0-9_]+\b/i }
];

const findFinalAnswerProtocolLeak = (turn: ProviderTurn) => {
	const visibleText = [turn.content ?? '', turn.reasoning ?? ''].join('\n');
	for (const pattern of FINAL_ANSWER_LEAK_PATTERNS) {
		if (pattern.regex.test(visibleText)) {
			return pattern.label;
		}
	}
	return null;
};

const decideAgentTurn = (
	turn: ProviderTurn,
	availableToolNames: Set<string>
): AgentTurnDecision => {
	const normalizedTurn = normalizeInlineXmlToolTurn(turn, availableToolNames);

	if (normalizedTurn.toolCalls.length > 0) {
		return {
			kind: 'tool_calls',
			turn: {
				...normalizedTurn,
				content: ''
			}
		};
	}

	const leak = findFinalAnswerProtocolLeak(normalizedTurn);
	if (leak) {
		return {
			kind: 'invalid',
			turn: normalizedTurn,
			reason: `Detected leaked tool protocol text: ${leak}.`
		};
	}

	return { kind: 'final', turn: normalizedTurn };
};

const buildAssistantToolCallMessage = (turn: ProviderTurn): JsonRecord => {
	const message: JsonRecord = {
		role: 'assistant',
		content: null,
		tool_calls: turn.toolCalls.map((toolCall) => ({
			...toolCall,
			function: {
				...toolCall.function,
				arguments: stringifyToolArguments(toolCall.function.arguments)
			}
		}))
	};
	const reasoning = turn.reasoning ?? '';

	// DeepSeek thinking mode requires reasoning_content to be replayed with
	// assistant tool-call messages when the tool result is sent back.
	if (reasoning.trim()) {
		message.reasoning_content = reasoning;
	}

	return message;
};

const normalizeToolCalls = (toolCalls: any[] = []): LocalToolCall[] =>
	toolCalls
		.map((toolCall, index) => ({
			id: toolCall?.id ?? `local-tool-call-${index}`,
			type: 'function' as const,
			function: {
				name: toolCall?.function?.name ?? '',
				arguments: toolCall?.function?.arguments ?? ''
			}
		}))
		.filter((toolCall) => toolCall.function.name);

const mergeToolCallDelta = (toolCalls: Map<number, LocalToolCall>, deltaToolCall: any) => {
	const index = Number.isInteger(deltaToolCall?.index) ? deltaToolCall.index : toolCalls.size;
	const existing =
		toolCalls.get(index) ??
		({
			id: deltaToolCall?.id ?? `local-tool-call-${index}`,
			type: 'function',
			function: { name: '', arguments: '' }
		} as LocalToolCall);

	if (deltaToolCall?.id) {
		existing.id = deltaToolCall.id;
	}

	if (deltaToolCall?.type) {
		existing.type = 'function';
	}

	if (typeof deltaToolCall?.function?.name === 'string') {
		existing.function.name += deltaToolCall.function.name;
	}

	if (typeof deltaToolCall?.function?.arguments === 'string') {
		const existingArguments =
			typeof existing.function.arguments === 'string'
				? existing.function.arguments
				: stringifyToolArguments(existing.function.arguments);
		existing.function.arguments = existingArguments + deltaToolCall.function.arguments;
	}

	toolCalls.set(index, existing);
};

const parseSseEvent = (eventText: string) =>
	eventText
		.split(/\r?\n/)
		.filter((line) => line.startsWith('data:'))
		.map((line) => line.slice(5).trimStart())
		.join('\n')
		.trim();

const parseStreamTurn = async (res: Response): Promise<ProviderTurn> => {
	if (!res.body) {
		return { content: '', toolCalls: [] };
	}

	const reader = res.body.getReader();
	const decoder = new TextDecoder();
	let buffer = '';
	let content = '';
	let reasoning = '';
	let usage: JsonRecord | undefined;
	const streamedToolCalls = new Map<number, LocalToolCall>();
	const startedAt = Date.now();

	while (true) {
		const { value, done } = await reader.read();
		if (done) {
			break;
		}

		buffer += decoder.decode(value, { stream: true });
		let separatorIndex = buffer.search(/\r?\n\r?\n/);

		while (separatorIndex !== -1) {
			const eventText = buffer.slice(0, separatorIndex);
			buffer = buffer.slice(separatorIndex + (buffer[separatorIndex] === '\r' ? 4 : 2));
			const dataText = parseSseEvent(eventText);

			if (dataText && dataText !== '[DONE]') {
				const chunk = JSON.parse(dataText);
				if (chunk?.usage) {
					usage = chunk.usage;
				}

				for (const choice of chunk?.choices ?? []) {
					const delta = choice?.delta ?? choice?.message ?? {};
					const contentDelta = contentToText(delta?.content);
					const reasoningDelta = getReasoningContent(delta);
					const toolCallDeltas = delta?.tool_calls ?? [];

					if (contentDelta) {
						content += contentDelta;
					}
					if (reasoningDelta) {
						reasoning += reasoningDelta;
					}
					for (const toolCallDelta of toolCallDeltas) {
						mergeToolCallDelta(streamedToolCalls, toolCallDelta);
					}
				}
			}

			separatorIndex = buffer.search(/\r?\n\r?\n/);
		}
	}

	return {
		content,
		toolCalls: normalizeToolCalls([...streamedToolCalls.values()]),
		reasoning,
		reasoningDone: true,
		reasoningDuration: reasoning
			? Math.max(0, Math.round((Date.now() - startedAt) / 1000))
			: undefined,
		usage
	};
};

const parseJsonTurn = async (res: Response): Promise<ProviderTurn> => {
	const raw = (await res.json()) as JsonRecord;
	const message = raw?.choices?.[0]?.message ?? {};

	return {
		content: contentToText(message?.content),
		toolCalls: normalizeToolCalls(message?.tool_calls ?? []),
		reasoning: getReasoningContent(message),
		reasoningDone: true,
		reasoningDuration: getReasoningContent(message) ? 0 : undefined,
		usage: raw?.usage,
		raw
	};
};

const parseProviderTurn = async (res: Response, stream: boolean): Promise<ProviderTurn> => {
	if (!res.ok) {
		const text = await res.text().catch(() => '');
		throw new Error(text || `Local provider request failed with status ${res.status}.`);
	}

	return stream ? parseStreamTurn(res) : parseJsonTurn(res);
};

const runWebSearchToolCall = async (
	toolCall: LocalToolCall,
	args: JsonRecord,
	context: LocalAgentRunContext
) => {
	if (toolCall.function.name === 'search_web') {
		const query = requiredString(args, 'query');
		const count = clampInteger(args.count, context.webSearchSettings.resultCount, 1, 10);

		context.statuses.push({
			action: 'web_search_queries_generated',
			queries: [query],
			done: true
		});

		const results = await searchWeb({
			...context.webSearchSettings,
			query,
			resultCount: count
		});
		context.sources = uniqueSources([...context.sources, ...createWebSearchSources(results)]);
		context.statuses.push(getWebSearchStatus(results));

		return jsonString({
			query,
			results: results.map((result, index) => ({
				index: index + 1,
				title: result.title,
				url: result.url,
				snippet: result.snippet,
				content: result.content,
				fetched: result.fetched,
				error: result.error
			}))
		});
	}

	if (toolCall.function.name === 'fetch_url') {
		const url = requiredString(args, 'url');
		const page = await fetchUrl({
			...context.webSearchSettings,
			url
		});
		context.sources = uniqueSources([...context.sources, ...createWebSearchSources([page])]);
		context.statuses.push(
			createStatus(page.fetched ? 'Fetched 1 web page' : 'Failed to fetch web page', {
				urls: [url],
				items: [{ title: page.title || url, url, snippet: page.error ?? '' }]
			})
		);

		return jsonString(page);
	}

	throw new Error(`Unknown local web search tool "${toolCall.function.name}".`);
};

const markWebSearchRetrying = (context: LocalAgentRunContext, error: unknown) => {
	if (!context.webSearchRetryReported) {
		context.statuses.push(
			createStatus(
				`Web search failed, retrying from DuckDuckGo before Bing fallback: ${getErrorMessage(error)}`,
				{
					urls: [],
					items: []
				}
			)
		);
	}

	context.webSearchRetryReported = true;
};

const markWebSearchUnavailable = (context: LocalAgentRunContext, error: unknown) => {
	if (!context.webSearchUnavailable) {
		context.statuses.push(
			createStatus(
				`Web search unavailable, continuing without web context: ${getErrorMessage(error)}`,
				{
					urls: [],
					items: []
				}
			)
		);
	}

	context.webSearchUnavailable = true;
};

const runToolCall = async (
	toolCall: LocalToolCall,
	context?: LocalAgentRunContext
): Promise<LocalToolMessage> => {
	let content: string;

	try {
		const args = parseToolArguments(toolCall);
		const missing = missingToolArguments(context?.toolParameters.get(toolCall.function.name), args);
		if (missing.length > 0) {
			content = jsonString(
				buildToolArgumentError(
					toolCall.function.name,
					`Missing required argument(s): ${missing.join(', ')}`,
					missing
				)
			);
		} else if (
			context?.webSearchEnabled &&
			(toolCall.function.name === 'search_web' || toolCall.function.name === 'fetch_url')
		) {
			content = await runWebSearchToolCall(toolCall, args, context);
		} else {
			content = await executeLocalTool(toolCall.function.name, args, {
				agentWorkspaceId: context?.agentWorkspaceId
			});
		}
	} catch (error) {
		if (
			context?.webSearchEnabled &&
			(toolCall.function.name === 'search_web' || toolCall.function.name === 'fetch_url')
		) {
			context.forceRagFallback = true;
			markWebSearchRetrying(context, error);
		}

		content = jsonString(buildToolArgumentError(toolCall.function.name, error));
	}

	return {
		role: 'tool',
		tool_call_id: toolCall.id,
		name: toolCall.function.name,
		content
	};
};

const buildRetryLimitTurn = (maxRetries: number): ProviderTurn => ({
	content: `Error: Local agent reached the maximum tool call retry limit (${maxRetries}).`,
	toolCalls: []
});

const buildInvalidTurnMessage = (): ProviderTurn => ({
	content:
		'Error: The local agent response was blocked because it contained internal tool protocol text. Please retry the request.',
	toolCalls: []
});

const buildRepairInstructionMessage = (
	reason: string,
	availableToolNames: Set<string>
): JsonRecord => {
	const toolList = [...availableToolNames].slice(0, 80).join(', ');
	return {
		role: 'user',
		content: [
			'Internal retry instruction. Your previous assistant turn was not shown to the user because it violated the local Agent output protocol.',
			`Reason: ${reason}`,
			'Regenerate the previous assistant turn using exactly one valid mode:',
			'1. If a tool is needed, call a provided tool using the native OpenAI tool_calls field only. Do not write tool syntax in message text.',
			'2. If no tool is needed, write only the user-visible final answer.',
			'Never output DSML, <tool_calls>, <details type="tool_calls">, invoke name=, parameter name=, or raw <agent_...> tags.',
			toolList ? `Available tool names: ${toolList}` : ''
		]
			.filter(Boolean)
			.join('\n')
	};
};

const shouldReviewLocalAgentFinal = (body: JsonRecord) => body?.params?.local_agent_review !== false;

const parseReviewResult = (content: string): FinalAnswerReviewResult | null => {
	const trimmed = content.trim();
	const jsonText = trimmed.match(/\{[\s\S]*\}/)?.[0] ?? trimmed;
	try {
		const parsed = JSON.parse(jsonText);
		if (typeof parsed?.approved !== 'boolean') {
			return null;
		}
		return {
			approved: parsed.approved,
			reason: typeof parsed.reason === 'string' ? parsed.reason : ''
		};
	} catch {
		return null;
	}
};

const reviewFinalAnswer = async ({
	answer,
	providerBody,
	requestProvider,
	onActiveController
}: {
	answer: string;
	providerBody: JsonRecord;
	requestProvider: LocalProviderRequest;
	onActiveController: (controller: AbortController) => void;
}): Promise<FinalAnswerReviewResult> => {
	try {
		const reviewBody: JsonRecord = {
			...providerBody,
			stream: false,
			temperature: 0,
			messages: [
				{
					role: 'system',
					content:
						'You are a strict reviewer for a local Agent output gateway. Return strict JSON only: {"approved": boolean, "reason": string}. Approve only if the answer is user-visible final text and contains no internal tool protocol text.'
				},
				{
					role: 'user',
					content: `Review this final answer for leaked tool protocol text. Reject DSML, <tool_calls>, invoke name=, parameter name=, <details type="tool_calls">, or raw <agent_...> tags.\n\n${answer}`
				}
			]
		};
		delete reviewBody.tools;
		delete reviewBody.tool_choice;
		delete reviewBody.stream_options;

		const [res, requestController] = await requestProvider(reviewBody);
		onActiveController(requestController);
		if (!res) {
			return { approved: false, reason: 'Final answer review request failed.' };
		}

		const reviewTurn = await parseProviderTurn(res, false);
		const parsed = parseReviewResult(reviewTurn.content);
		return parsed ?? { approved: false, reason: 'Final answer review returned invalid JSON.' };
	} catch (error) {
		return {
			approved: false,
			reason: `Final answer review failed: ${getErrorMessage(error)}`
		};
	}
};

const buildJsonResponse = (turn: ProviderTurn) => {
	const raw = turn.raw ? structuredClone(turn.raw) : {};
	if (!Array.isArray(raw.choices) || !raw.choices[0]?.message) {
		raw.choices = [
			{
				index: 0,
				message: { role: 'assistant', content: turn.content },
				finish_reason: 'stop'
			}
		];
	} else {
		raw.choices[0].message = {
			...raw.choices[0].message,
			role: raw.choices[0].message.role ?? 'assistant',
			content: turn.content
		};
		delete raw.choices[0].message.tool_calls;
		delete raw.choices[0].message.reasoning_content;
		delete raw.choices[0].message.reasoning;
		delete raw.choices[0].message.thinking;
	}

	if (turn.usage) {
		raw.usage = turn.usage;
	}
	if (turn.sources?.length) {
		raw.sources = turn.sources;
	}
	if (turn.statuses?.length) {
		raw.statuses = turn.statuses;
	}

	return new Response(JSON.stringify(raw), {
		headers: { 'Content-Type': 'application/json' }
	});
};

const buildStreamResponse = (
	run: (callbacks?: { emitContentSnapshot: (content: string) => void }) => Promise<ProviderTurn>,
	controller: AbortController
) => {
	const encoder = new TextEncoder();
	const enqueueFinalDeltaContent = (
		content: string,
		enqueueEvent: (data: JsonRecord) => void
	) => {
		if (!content.trim()) {
			return;
		}

		const chunkSize = 24;
		for (let index = 0; index < content.length; index += chunkSize) {
			enqueueEvent({
				choices: [
					{
						index: 0,
						delta: { content: content.slice(index, index + chunkSize) },
						finish_reason: null
					}
				]
			});
		}
	};

	return new Response(
		new ReadableStream({
			async start(streamController) {
				const enqueueEvent = (data: JsonRecord) => {
					if (!controller.signal.aborted) {
						streamController.enqueue(encoder.encode(`data: ${JSON.stringify(data)}\n\n`));
					}
				};

				try {
					const turn = await run({
						emitContentSnapshot: (content: string) => {
							enqueueEvent({ content });
						}
					});
					for (const status of turn.statuses ?? []) {
						enqueueEvent({ status });
					}

					if (!controller.signal.aborted && turn.sources?.length) {
						enqueueEvent({ sources: turn.sources });
					}

					if (!controller.signal.aborted) {
						enqueueFinalDeltaContent(turn.streamContent ?? turn.content, enqueueEvent);
					}

					if (!controller.signal.aborted && turn.usage) {
						enqueueEvent({ usage: turn.usage });
					}

					if (!controller.signal.aborted) {
						streamController.enqueue(encoder.encode('data: [DONE]\n\n'));
					}
					streamController.close();
				} catch (error) {
					streamController.error(error);
				}
			},
			cancel() {
				controller.abort();
			}
		}),
		{
			headers: {
				'Content-Type': 'text/event-stream',
				'Cache-Control': 'no-cache',
				Connection: 'keep-alive'
			}
		}
	);
};

const attachLocalAgentMetadata = (
	turn: ProviderTurn,
	context: LocalAgentRunContext
): ProviderTurn => ({
	...turn,
	sources: uniqueSources([...(turn.sources ?? []), ...context.sources]),
	statuses: [...(turn.statuses ?? []), ...context.statuses]
});

const runWebSearchRagFallback = async ({
	providerBody,
	requestProvider,
	context,
	activeController,
	onActiveController
}: {
	providerBody: JsonRecord;
	requestProvider: LocalProviderRequest;
	context: LocalAgentRunContext;
	activeController: AbortController | null;
	onActiveController: (controller: AbortController) => void;
}) => {
	const originalMessages = [...(providerBody.messages ?? [])];

	const requestWithoutWebContext = async () => {
		const [res, requestController] = await requestProvider(providerBody);
		onActiveController(requestController);
		if (!res) {
			throw new Error('Local provider request failed.');
		}
		return parseProviderTurn(res, Boolean(providerBody.stream));
	};

	const query = getLastUserQuery(originalMessages);

	if (!query) {
		context.statuses.push(createStatus('No search query generated'));
		return requestWithoutWebContext();
	}

	try {
		context.statuses.push({
			action: 'web_search_queries_generated',
			queries: [query],
			done: true
		});

		const results = await searchWeb({
			...context.webSearchSettings,
			engine: 'auto',
			query
		});
		context.sources = uniqueSources([...context.sources, ...createWebSearchSources(results)]);
		context.statuses.push(getWebSearchStatus(results));

		if (results.length === 0) {
			const [res, requestController] = await requestProvider(providerBody);
			onActiveController(requestController);
			if (!res) {
				throw new Error('Local provider request failed.');
			}
			return parseProviderTurn(res, Boolean(providerBody.stream));
		}

		const webContext = createSearchContext(results);
		const fallbackMessages = [
			{
				role: 'system',
				content:
					'Use the following web search sources when they are relevant. Cite URLs in the answer when using them.\n\n' +
					webContext
			},
			...originalMessages
		];

		const [res, requestController] = await requestProvider({
			...providerBody,
			messages: fallbackMessages
		});
		onActiveController(requestController);
		if (!res) {
			throw new Error('Local provider request failed.');
		}

		return parseProviderTurn(res, Boolean(providerBody.stream));
	} catch (error) {
		markWebSearchUnavailable(context, error);

		return requestWithoutWebContext();
	} finally {
		activeController?.signal.throwIfAborted?.();
	}
};

export const requestLocalAgentChatCompletion = async ({
	body,
	providerBody,
	requestProvider,
	maxRetries = getLocalAgentMaxToolCallRetries()
}: {
	body: JsonRecord;
	providerBody: JsonRecord;
	requestProvider: LocalProviderRequest;
	maxRetries?: number;
}): Promise<[Response | null, AbortController]> => {
	const controller = new AbortController();
	let activeController: AbortController | null = null;

	const messages = [...(providerBody.messages ?? [])];
	const webSearchEnabled = shouldUseLocalWebSearchAgentLoop(body);
	const codeInterpreterEnabled = shouldUseLocalCodeInterpreterAgentLoop(body);
	const androidDeviceToolsEnabled = body?.features?.android_device_tools === true;
	const nativeFunctionCallingEnabled = body?.params?.function_calling === 'native';
	const broadLocalToolsEnabled =
		nativeFunctionCallingEnabled ||
		androidDeviceToolsEnabled ||
		(!webSearchEnabled && !codeInterpreterEnabled);
	const localSettings = webSearchEnabled || androidDeviceToolsEnabled ? await getLocalSettings() : null;
	const agentWorkspaceId =
		typeof body?.params?.agent_workspace_id === 'string' && body.params.agent_workspace_id.trim()
			? body.params.agent_workspace_id.trim()
			: null;
	const webSearchFallbackEnabled = webSearchEnabled && !agentWorkspaceId;
	const context: LocalAgentRunContext = {
		toolParameters: new Map(),
		webSearchEnabled,
		webSearchSettings: normalizeLocalWebSearchSettings(localSettings?.localWebSearch ?? {}),
		sources: [],
		statuses: webSearchEnabled
			? [createStatus('Searching the web', { done: false, urls: [], items: [] })]
			: [],
		displayOutput: [],
		toolCallCount: 0,
		forceRagFallback: false,
		webSearchRetryReported: false,
		webSearchUnavailable: false,
		agentWorkspaceId
	};
	const maxModelRounds = webSearchFallbackEnabled
		? DEFAULT_LOCAL_WEB_SEARCH_MAX_MODEL_ROUNDS
		: maxRetries + 1;
	const maxToolCalls = webSearchFallbackEnabled
		? DEFAULT_LOCAL_WEB_SEARCH_MAX_TOOL_CALLS
		: Number.POSITIVE_INFINITY;
	const tools = (await getAvailableLocalTools({
		includeWebSearch: webSearchEnabled,
		includeAndroidTools: androidDeviceToolsEnabled,
		includeLocationTool: localSettings?.userLocation === true,
		agentWorkspaceId
	}))
		.filter(
			(tool) =>
				broadLocalToolsEnabled ||
				(webSearchEnabled && (tool.category === 'web' || tool.category === 'math')) ||
				(codeInterpreterEnabled && (tool.category === 'code' || tool.category === 'math')) ||
				(tool.category === 'agent' && (agentWorkspaceId || tool.requiresWorkspace === false))
		)
		.map(toProviderTool);
	const availableToolNames = new Set(
		tools
			.map((tool) => tool.function.name)
			.filter((name): name is string => typeof name === 'string')
	);
	context.toolParameters = new Map(
		tools.map((tool) => [tool.function.name, tool.function.parameters])
	);
	const agentBody = {
		...providerBody,
		tools,
		tool_choice: 'auto'
	};
	const keepAliveToken = createLocalAgentKeepAliveToken();
	let keepAliveStarted = false;
	let keepAliveReleased = false;

	const beginKeepAlive = async () => {
		if (keepAliveStarted || keepAliveReleased) return;
		keepAliveStarted = await startLocalAgentKeepAlive(keepAliveToken);
	};

	const releaseKeepAlive = async () => {
		if (!keepAliveStarted || keepAliveReleased) return;
		keepAliveReleased = true;
		await stopLocalAgentKeepAlive(keepAliveToken);
	};

	controller.signal.addEventListener('abort', () => {
		activeController?.abort();
		void releaseKeepAlive();
	});

	// Workspace Agent answers already pass the deterministic protocol-leak gate above.
	// Do not ask the same OpenAI-compatible model to meta-review those answers: in real
	// homework runs it can reject ordinary file paths and code snippets as tool protocol,
	// replacing an otherwise valid packaged result with a generic error.
	const reviewFinalAnswers = shouldReviewLocalAgentFinal(body) && !agentWorkspaceId;
	let repairAttempts = 0;
	const enqueueRepairRetry = (reason: string) => {
		if (repairAttempts >= 1) {
			return false;
		}
		repairAttempts += 1;
		messages.push(buildRepairInstructionMessage(reason, availableToolNames));
		return true;
	};

	const markWebSearchComplete = () => {
		if (!webSearchEnabled) {
			return;
		}
		context.statuses = context.statuses.map((status) =>
			status.description === 'Searching the web' ? { ...status, done: true } : status
		);
	};

	const requestWebSearchFallbackTurn = () =>
		runWebSearchRagFallback({
			providerBody,
			requestProvider,
			context,
			activeController,
			onActiveController: (nextController) => {
				activeController = nextController;
			}
		});

	type PreparedAgentTurn =
		| { kind: 'final'; turn: ProviderTurn }
		| { kind: 'retry' }
		| { kind: 'tool_calls'; turn: ProviderTurn };

	const prepareFinalTurn = async (turn: ProviderTurn): Promise<PreparedAgentTurn> => {
		const decision = decideAgentTurn(turn, availableToolNames);

		if (decision.kind === 'tool_calls') {
			return decision;
		}

		if (decision.kind === 'invalid') {
			if (enqueueRepairRetry(decision.reason)) {
				return { kind: 'retry' };
			}
			return {
				kind: 'final',
				turn: finalizeLocalAgentTurn(
					attachLocalAgentMetadata(buildInvalidTurnMessage(), context),
					context
				)
			};
		}

		const finalAnswer = formatTurnContent(decision.turn);
		if (reviewFinalAnswers) {
			const review = await reviewFinalAnswer({
				answer: finalAnswer,
				providerBody,
				requestProvider,
				onActiveController: (nextController) => {
					activeController = nextController;
				}
			});

			if (!review.approved) {
				const reason = review.reason || 'Final answer review rejected the response.';
				if (enqueueRepairRetry(reason)) {
					return { kind: 'retry' };
				}
				return {
					kind: 'final',
					turn: finalizeLocalAgentTurn(
						attachLocalAgentMetadata(buildInvalidTurnMessage(), context),
						context
					)
				};
			}
		}

		markWebSearchComplete();
		return {
			kind: 'final',
			turn: finalizeLocalAgentTurn(attachLocalAgentMetadata(decision.turn, context), context)
		};
	};

	const run = async (callbacks?: { emitContentSnapshot: (content: string) => void }) => {
		context.emitContentSnapshot = callbacks?.emitContentSnapshot;

		try {
			for (let iteration = 0; iteration < maxModelRounds; iteration += 1) {
				if (controller.signal.aborted) {
					throw new DOMException('The local agent request was aborted.', 'AbortError');
				}

				let res: Response | null = null;
				let turn: ProviderTurn | null = null;
				try {
					const [providerResponse, requestController] = await requestProvider({
						...agentBody,
						messages
					});
					res = providerResponse;
					activeController = requestController;
				} catch (error) {
					if (webSearchFallbackEnabled) {
						const fallbackDecision = await prepareFinalTurn(await requestWebSearchFallbackTurn());
						if (fallbackDecision.kind === 'retry') {
							continue;
						}
						if (fallbackDecision.kind === 'final') {
							return fallbackDecision.turn;
						}
						turn = fallbackDecision.turn;
					} else {
						throw error;
					}
				}

				if (!res && !turn) {
					throw new Error('Local provider request failed.');
				}

				if (res) {
					try {
						turn = await parseProviderTurn(res, Boolean(providerBody.stream));
					} catch (error) {
						if (webSearchFallbackEnabled) {
							const fallbackDecision = await prepareFinalTurn(await requestWebSearchFallbackTurn());
							if (fallbackDecision.kind === 'retry') {
								continue;
							}
							if (fallbackDecision.kind === 'final') {
								return fallbackDecision.turn;
							}
							turn = fallbackDecision.turn;
						} else {
							throw error;
						}
					}
				}
				if (!turn) {
					throw new Error('Local provider request failed.');
				}

				const decision = await prepareFinalTurn(turn);

				if (decision.kind === 'retry') {
					continue;
				}

				if (decision.kind === 'final') {
					if (webSearchFallbackEnabled && iteration === 0 && context.sources.length === 0) {
						const fallbackDecision = await prepareFinalTurn(await requestWebSearchFallbackTurn());
						if (fallbackDecision.kind === 'retry') {
							continue;
						}
						if (fallbackDecision.kind === 'final') {
							return fallbackDecision.turn;
						}
						turn = fallbackDecision.turn;
					} else {
						return decision.turn;
					}
				}

				turn = decision.kind === 'tool_calls' ? decision.turn : turn;
				context.toolCallCount += turn.toolCalls.length;
				if (context.toolCallCount > maxToolCalls || iteration >= maxModelRounds - 1) {
					if (webSearchFallbackEnabled) {
						context.forceRagFallback = true;
						const fallbackDecision = await prepareFinalTurn(await requestWebSearchFallbackTurn());
						if (fallbackDecision.kind === 'retry') {
							continue;
						}
						return fallbackDecision.kind === 'final'
							? fallbackDecision.turn
							: finalizeLocalAgentTurn(
									attachLocalAgentMetadata(buildRetryLimitTurn(maxRetries), context),
									context
								);
					}
					return finalizeLocalAgentTurn(buildRetryLimitTurn(maxRetries), context);
				}

				for (const toolCall of turn.toolCalls) {
					context.displayOutput.push({
						type: 'function_call',
						id: toolCall.id || outputId('fc'),
						call_id: toolCall.id,
						name: toolCall.function.name,
						arguments: stringifyToolArguments(toolCall.function.arguments) || '{}',
						status: 'in_progress'
					});
				}
				emitLocalDisplaySnapshot(context);

				messages.push(buildAssistantToolCallMessage(turn));

				const toolMessages = await Promise.all(
					turn.toolCalls.map((toolCall) => runToolCall(toolCall, context))
				);

				for (const toolCall of turn.toolCalls) {
					const callItem = context.displayOutput.find(
						(item) => item.type === 'function_call' && item.call_id === toolCall.id
					);
					if (callItem?.type === 'function_call') {
						callItem.status = 'completed';
					}
				}

				for (const toolMessage of toolMessages) {
					context.displayOutput.push({
						type: 'function_call_output',
						id: outputId('fco'),
						call_id: toolMessage.tool_call_id,
						output: [{ type: 'input_text', text: toolMessage.content }],
						status: 'completed'
					});
				}
				emitLocalDisplaySnapshot(context);

				messages.push(...toolMessages);

				if (webSearchFallbackEnabled && context.forceRagFallback) {
					const fallbackDecision = await prepareFinalTurn(await requestWebSearchFallbackTurn());
					if (fallbackDecision.kind === 'retry') {
						continue;
					}
					return fallbackDecision.kind === 'final'
						? fallbackDecision.turn
						: finalizeLocalAgentTurn(
								attachLocalAgentMetadata(buildRetryLimitTurn(maxRetries), context),
								context
							);
				}
			}

			if (webSearchFallbackEnabled) {
				const fallbackDecision = await prepareFinalTurn(await requestWebSearchFallbackTurn());
				if (fallbackDecision.kind === 'final') {
					return fallbackDecision.turn;
				}
			}

			return finalizeLocalAgentTurn(buildRetryLimitTurn(maxRetries), context);
		} finally {
			context.emitContentSnapshot = undefined;
			await releaseKeepAlive();
		}
	};

	await beginKeepAlive();

	if (providerBody.stream) {
		return [buildStreamResponse(run, controller), controller];
	}

	return [buildJsonResponse(await run()), controller];
};
