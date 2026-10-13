import { beforeEach, describe, expect, it, vi } from 'vitest';

const native = vi.hoisted(() => ({ executeTool: vi.fn(), listTools: vi.fn() }));
vi.mock('@capacitor/core', () => ({
	Capacitor: { isNativePlatform: vi.fn(() => true), getPlatform: vi.fn(() => 'android') },
	registerPlugin: vi.fn(() => native)
}));

import { Capacitor } from '@capacitor/core';
import { executeNativeAgentTool, listNativeAgentTools } from './native-agent-tools';

beforeEach(() => {
	vi.clearAllMocks();
	vi.mocked(Capacitor.isNativePlatform).mockReturnValue(true);
	vi.mocked(Capacitor.getPlatform).mockReturnValue('android');
	native.executeTool.mockResolvedValue({ output: { ok: true } });
});

describe('native Agent bridge contract', () => {
	it('generates host request IDs outside model arguments and preserves explicit retry IDs', async () => {
		await executeNativeAgentTool({ toolName: 'agent_mail_send', arguments: { subject: 'test' } });
		expect(native.executeTool).toHaveBeenCalledWith({
			toolName: 'agent_mail_send', arguments: { subject: 'test' }, requestId: expect.stringMatching(/^[0-9a-f-]{36}$/)
		});
		await executeNativeAgentTool({ toolName: 'agent_mail_send', requestId: 'retry-1' });
		expect(native.executeTool).toHaveBeenLastCalledWith({ toolName: 'agent_mail_send', requestId: 'retry-1' });
	});

	it('passes through native metadata and refuses unsupported clients', async () => {
		const tools = [{ type: 'function', requiresWorkspace: false, policy: { dataScope: 'academic' }, function: { name: 'agent_academic_exams' } }];
		native.listTools.mockResolvedValue({ tools });
		expect(await listNativeAgentTools()).toEqual(tools);
		vi.mocked(Capacitor.isNativePlatform).mockReturnValue(false);
		await expect(executeNativeAgentTool({ toolName: 'agent_academic_exams' })).rejects.toThrow('only available');
		expect(native.executeTool).not.toHaveBeenCalled();
	});
});
