import { Capacitor, registerPlugin } from '@capacitor/core';
import { dedupeNativeAgentGeneratedFiles } from './generated-files';

type JsonRecord = Record<string, any>;

export type NativeAgentToolDefinition = {
	type: 'function';
	requiresWorkspace?: boolean;
	policy?: NativeAgentToolPolicy;
	function: {
		name: string;
		description: string;
		parameters: JsonRecord;
	};
};

export type NativeAgentToolPolicy = {
	operation: 'read' | 'command' | 'unavailable';
	dataScope: string;
	requiresConfirmation: boolean;
	requiresForeground: boolean;
	requiresIdempotencyKey: boolean;
	timeoutMs: number;
	maxInputBytes: number;
	maxOutputBytes: number;
};

export type NativeAgentAttachment = {
	displayName: string;
	relativePath: string;
	mimeType?: string | null;
	sizeBytes?: number | null;
	extractedDir?: string | null;
	extractedFiles?: string[];
	extractionError?: string | null;
};

export type NativeAgentAttachmentFailure = {
	filename: string;
	message: string;
};

export type NativeAgentHomeworkDraft = {
	hasPending: boolean;
	workspaceId?: string;
	draft?: string;
	attachments?: NativeAgentAttachment[];
	failedAttachments?: NativeAgentAttachmentFailure[];
};

export type NativeAgentGeneratedFile = {
	displayName: string;
	relativePath: string;
	mimeType?: string | null;
	sizeBytes?: number | null;
	role?: string | null;
};

export type NativeAgentGeneratedFileSaveResult = {
	saved: boolean;
	uri?: string;
	displayName?: string;
	location?: string;
	mimeType?: string | null;
	sizeBytes?: number | null;
};

export type NativeAgentGeneratedFilePreview = {
	previewable: boolean;
	kind?: 'pdf' | 'docx' | 'markdown' | 'code' | 'text';
	displayName?: string;
	relativePath?: string;
	mimeType?: string | null;
	sizeBytes?: number | null;
	encoding?: 'base64' | 'utf-8';
	base64?: string;
	text?: string;
	reason?: string;
};

type NativeAgentToolsPlugin = {
	consumePendingHomeworkDraft(): Promise<NativeAgentHomeworkDraft>;
	listTools(): Promise<{ tools: NativeAgentToolDefinition[] }>;
	executeTool(options: {
		requestId?: string;
		workspaceId?: string | null;
		toolName: string;
		arguments?: JsonRecord;
	}): Promise<JsonRecord>;
	beginKeepAlive(options: { token: string; reason?: string | null }): Promise<{ active: boolean }>;
	endKeepAlive(options: { token: string }): Promise<{ active: boolean }>;
	listGeneratedFiles(options: { workspaceId: string }): Promise<{ files: NativeAgentGeneratedFile[] }>;
	saveGeneratedFile(options: {
		workspaceId: string;
		relativePath: string;
	}): Promise<NativeAgentGeneratedFileSaveResult>;
	readGeneratedFilePreview(options: {
		workspaceId: string;
		relativePath: string;
	}): Promise<NativeAgentGeneratedFilePreview>;
};

const NativeAgentTools = registerPlugin<NativeAgentToolsPlugin>('NativeAgentTools');

export const supportsNativeAgentTools = () =>
	Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android';

const requireNativeAgentTools = () => {
	if (!supportsNativeAgentTools()) {
		throw new Error('Native Agent tools are only available on Android local-first clients.');
	}
};

export const consumePendingHomeworkDraft = async (): Promise<NativeAgentHomeworkDraft> => {
	if (!supportsNativeAgentTools()) {
		return { hasPending: false };
	}

	return NativeAgentTools.consumePendingHomeworkDraft();
};

export const listNativeAgentTools = async () => {
	requireNativeAgentTools();
	return (await NativeAgentTools.listTools()).tools ?? [];
};

export const executeNativeAgentTool = async (options: {
	requestId?: string;
	workspaceId?: string | null;
	toolName: string;
	arguments?: JsonRecord;
}) => {
	requireNativeAgentTools();
	return NativeAgentTools.executeTool({ ...options, requestId: options.requestId ?? crypto.randomUUID() });
};

export const beginNativeAgentKeepAlive = async (options: {
	token: string;
	reason?: string | null;
}) => {
	if (!supportsNativeAgentTools()) {
		return false;
	}

	await NativeAgentTools.beginKeepAlive(options);
	return true;
};

export const endNativeAgentKeepAlive = async (options: { token: string }) => {
	if (!supportsNativeAgentTools()) {
		return false;
	}

	await NativeAgentTools.endKeepAlive(options);
	return true;
};

export const listNativeAgentGeneratedFiles = async (
	workspaceId?: string | null
): Promise<NativeAgentGeneratedFile[]> => {
	if (!workspaceId || !supportsNativeAgentTools()) {
		return [];
	}

	const response = await NativeAgentTools.listGeneratedFiles({ workspaceId });
	return dedupeNativeAgentGeneratedFiles(response.files ?? []);
};

export const saveNativeAgentGeneratedFile = async (options: {
	workspaceId: string;
	relativePath: string;
}) => {
	requireNativeAgentTools();
	return NativeAgentTools.saveGeneratedFile(options);
};

export const readNativeAgentGeneratedFilePreview = async (options: {
	workspaceId: string;
	relativePath: string;
}) => {
	requireNativeAgentTools();
	return NativeAgentTools.readGeneratedFilePreview(options);
};

export type NativeMailSendConfirmation = {
	to: string[];
	cc: string[];
	bcc: string[];
	subject: string;
	body: string;
	isHtml: boolean;
	attachmentCount: number;
};

type MailSendConfirmationHandler = (request: NativeMailSendConfirmation) => Promise<boolean> | boolean;

let mailSendConfirmationHandler: MailSendConfirmationHandler | null = null;

export const registerMailSendConfirmationHandler = (handler: MailSendConfirmationHandler) => {
	mailSendConfirmationHandler = handler;
	return () => {
		if (mailSendConfirmationHandler === handler) {
			mailSendConfirmationHandler = null;
		}
	};
};

export const confirmNativeMailSend = async (request: NativeMailSendConfirmation) => {
	if (!mailSendConfirmationHandler) {
		throw new Error('mail_send_confirmation_unavailable');
	}

	const confirmed = await mailSendConfirmationHandler(request);
	if (!confirmed) {
		throw new Error('user_denied');
	}
};
