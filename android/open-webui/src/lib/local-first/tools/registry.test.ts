import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../db', () => ({
	createLocalMemory: vi.fn(), createLocalNote: vi.fn(), createLocalTask: vi.fn(),
	deleteLocalMemory: vi.fn(), getLocalChat: vi.fn(), getLocalFileRecord: vi.fn(),
	listLocalChats: vi.fn(), listLocalFiles: vi.fn(), listLocalTasks: vi.fn(),
	searchLocalKnowledge: vi.fn(), searchLocalMemories: vi.fn(), searchLocalNotes: vi.fn(),
	updateLocalTask: vi.fn()
}));
vi.mock('../android-tools', () => ({
	capturePhoto: vi.fn(), cancelNotification: vi.fn(), createCalendarEvent: vi.fn(),
	deleteCalendarEvent: vi.fn(), getCurrentLocation: vi.fn(), getDeviceContext: vi.fn(),
	listCalendarEvents: vi.fn(), pickMedia: vi.fn(), scheduleNotification: vi.fn(),
	startAudioRecording: vi.fn(), stopAudioRecording: vi.fn(),
	supportsNativeAndroidTools: vi.fn(() => false), updateCalendarEvent: vi.fn()
}));
vi.mock('../native-agent-tools', () => ({
	confirmNativeMailSend: vi.fn(), executeNativeAgentTool: vi.fn(),
	listNativeAgentTools: vi.fn(), supportsNativeAgentTools: vi.fn(() => true)
}));

import { executeRegisteredLocalTool, getAvailableLocalTools } from './registry';
import { executeNativeAgentTool, listNativeAgentTools, supportsNativeAgentTools } from '../native-agent-tools';

const query = {
	type: 'function' as const,
	requiresWorkspace: false,
	policy: {
		operation: 'read' as const, dataScope: 'academic', requiresConfirmation: true,
		requiresForeground: true, requiresIdempotencyKey: false, timeoutMs: 60000,
		maxInputBytes: 262144, maxOutputBytes: 1048576
	},
	function: { name: 'agent_academic_agenda', description: 'Campus agenda', parameters: { type: 'object' } }
};
const file = {
	type: 'function' as const,
	requiresWorkspace: true,
	function: { name: 'agent_file_read', description: 'Workspace file', parameters: { type: 'object' } }
};

beforeEach(() => {
	vi.clearAllMocks();
	vi.mocked(supportsNativeAgentTools).mockReturnValue(true);
	vi.mocked(listNativeAgentTools).mockResolvedValue([query, file]);
	vi.mocked(executeNativeAgentTool).mockResolvedValue({ output: { ok: true, data: {} } });
});

describe('native Agent registration and workspace policy', () => {
	it('registers native todo commands and navigation without workspace and preserves receipts', async () => {
		const names = ['agent_todo_list', 'agent_todo_create', 'agent_todo_update', 'agent_todo_delete',
			'agent_reminder_set', 'agent_reminder_cancel', 'agent_app_open_page'];
		vi.mocked(listNativeAgentTools).mockResolvedValue(names.map((name) => ({
			...query,
			policy: { ...query.policy, operation: name === 'agent_todo_list' ? 'read' : 'command',
				requiresIdempotencyKey: name !== 'agent_todo_list', dataScope: 'todo' },
			function: { ...query.function, name }
		})));
		const available = await getAvailableLocalTools();
		expect(names.every((name) => available.some((tool) => tool.function.name === name))).toBe(true);
		vi.mocked(executeNativeAgentTool).mockResolvedValue({ output: { ok: true, todo: { id: 7, updatedAt: 'v1' } } });
		const result = JSON.parse(await executeRegisteredLocalTool('agent_todo_create',
			{ title: 'Example', date: '2026-10-13' }, { requestId: 'todo-create-1' }));
		expect(result.output.todo.id).toBe(7);
		expect(executeNativeAgentTool).toHaveBeenCalledWith({ workspaceId: '', toolName: 'agent_todo_create',
			arguments: { title: 'Example', date: '2026-10-13' }, requestId: 'todo-create-1' });
	});
	it('advertises metadata and executes non-mail tools without a workspace', async () => {
		const available = await getAvailableLocalTools();
		expect(available.find((tool) => tool.function.name === query.function.name)?.policy).toEqual(query.policy);
		expect(available.some((tool) => tool.function.name === file.function.name)).toBe(false);
		const result = JSON.parse(await executeRegisteredLocalTool(query.function.name, { refresh: false }));
		expect(result.output.ok).toBe(true);
		expect(executeNativeAgentTool).toHaveBeenCalledWith({
			workspaceId: '', toolName: query.function.name, arguments: { refresh: false }
		});
	});

	it('does not grant workspace access based on a mail prefix', async () => {
		vi.mocked(listNativeAgentTools).mockResolvedValue([{ ...file, function: { ...file.function, name: 'agent_mail_fake' } }]);
		const result = JSON.parse(await executeRegisteredLocalTool('agent_mail_fake'));
		expect(result.error).toContain('workspace');
		expect(executeNativeAgentTool).not.toHaveBeenCalled();
	});

	it('fails closed for unknown tools, missing metadata and unavailable native runtime', async () => {
		expect(JSON.parse(await executeRegisteredLocalTool('agent_unknown')).error).toContain('not registered');
		vi.mocked(listNativeAgentTools).mockResolvedValue([{ ...file, requiresWorkspace: undefined }]);
		expect(JSON.parse(await executeRegisteredLocalTool(file.function.name)).error).toContain('workspace');
		vi.mocked(supportsNativeAgentTools).mockReturnValue(false);
		expect(JSON.parse(await executeRegisteredLocalTool(query.function.name)).error).toContain('unavailable');
		expect(executeNativeAgentTool).not.toHaveBeenCalled();
	});

	it('preserves native rejection codes and forwards an explicit retry key', async () => {
		vi.mocked(executeNativeAgentTool).mockResolvedValue({ output: { ok: false, error: 'user_denied' } });
		const result = JSON.parse(await executeRegisteredLocalTool(query.function.name, {}, { requestId: 'retry-1' }));
		expect(result.output.error).toBe('user_denied');
		expect(executeNativeAgentTool).toHaveBeenCalledWith(expect.objectContaining({ requestId: 'retry-1' }));
	});
});
