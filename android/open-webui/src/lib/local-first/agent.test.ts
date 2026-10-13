import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('./db', () => ({
	getLocalSettings: vi.fn(),
	listLocalFiles: vi.fn(),
	getLocalFileRecord: vi.fn(),
	listLocalChats: vi.fn(),
	getLocalChat: vi.fn(),
	searchLocalKnowledge: vi.fn(),
	createLocalMemory: vi.fn(),
	searchLocalMemories: vi.fn(),
	deleteLocalMemory: vi.fn(),
	createLocalNote: vi.fn(),
	searchLocalNotes: vi.fn(),
	createLocalTask: vi.fn(),
	updateLocalTask: vi.fn(),
	listLocalTasks: vi.fn()
}));

vi.mock('./android-tools', () => ({
	supportsNativeAndroidTools: vi.fn(() => false),
	getCurrentLocation: vi.fn(),
	getDeviceContext: vi.fn(),
	pickMedia: vi.fn(),
	capturePhoto: vi.fn(),
	startAudioRecording: vi.fn(),
	stopAudioRecording: vi.fn(),
	listCalendarEvents: vi.fn(),
	createCalendarEvent: vi.fn(),
	updateCalendarEvent: vi.fn(),
	deleteCalendarEvent: vi.fn(),
	scheduleNotification: vi.fn(),
	cancelNotification: vi.fn()
}));

vi.mock('./web-search', () => ({
	supportsNativeWebSearch: vi.fn(() => false),
	searchWeb: vi.fn(),
	fetchUrl: vi.fn(),
	createWebSearchSources: vi.fn((results: any[]) =>
		results.map((result) => ({
			source: { id: result.url, name: result.url, url: result.url },
			document: [result.content || result.snippet || result.title || result.url],
			metadata: [{ source: result.url, name: result.title || result.url, url: result.url }],
			distances: []
		}))
	),
	getWebSearchStatus: vi.fn((results: any[]) => ({
		action: 'web_search',
		description: 'Searched {{count}} sites',
		done: true,
		urls: results.map((result) => result.url),
		items: results
	}))
}));

vi.mock('./native-agent-tools', () => ({
	supportsNativeAgentTools: vi.fn(() => false),
	listNativeAgentTools: vi.fn(),
	executeNativeAgentTool: vi.fn(),
	beginNativeAgentKeepAlive: vi.fn(),
	endNativeAgentKeepAlive: vi.fn(),
	confirmNativeMailSend: vi.fn()
}));

import {
	executeLocalTool,
	LOCAL_AGENT_TOOLS,
	requestLocalAgentChatCompletion,
	shouldUseLocalAgentLoop
} from './agent';
import {
	getLocalChat,
	getLocalFileRecord,
	getLocalSettings,
	listLocalChats,
	listLocalFiles
} from './db';
import { getCurrentLocation, supportsNativeAndroidTools } from './android-tools';
import { searchWeb, supportsNativeWebSearch } from './web-search';
import {
	beginNativeAgentKeepAlive,
	confirmNativeMailSend,
	endNativeAgentKeepAlive,
	executeNativeAgentTool,
	listNativeAgentTools,
	supportsNativeAgentTools
} from './native-agent-tools';

const providerJsonResponse = (message: Record<string, any>) =>
	new Response(
		JSON.stringify({
			id: 'chatcmpl-test',
			object: 'chat.completion',
			choices: [
				{
					index: 0,
					message,
					finish_reason: message.tool_calls?.length ? 'tool_calls' : 'stop'
				}
			]
		}),
		{ headers: { 'Content-Type': 'application/json' } }
	);

const providerStreamResponse = (events: Record<string, any>[]) => {
	const encoder = new TextEncoder();

	return new Response(
		new ReadableStream({
			start(controller) {
				for (const event of events) {
					controller.enqueue(encoder.encode(`data: ${JSON.stringify(event)}\n\n`));
				}
				controller.enqueue(encoder.encode('data: [DONE]\n\n'));
				controller.close();
			}
		}),
		{ headers: { 'Content-Type': 'text/event-stream' } }
	);
};

const controlledProviderStreamResponse = () => {
	const encoder = new TextEncoder();
	let streamController: ReadableStreamDefaultController<Uint8Array> | undefined;

	const response = new Response(
		new ReadableStream({
			start(controller) {
				streamController = controller;
			}
		}),
		{ headers: { 'Content-Type': 'text/event-stream' } }
	);

	return {
		response,
		send: (event: Record<string, any>) => {
			streamController?.enqueue(encoder.encode(`data: ${JSON.stringify(event)}\n\n`));
		},
		done: () => {
			streamController?.enqueue(encoder.encode('data: [DONE]\n\n'));
			streamController?.close();
		}
	};
};

const parseSseJsonEvents = (streamText = '') =>
	streamText
		.split(/\r?\n\r?\n/)
		.map((eventText) =>
			eventText
				.split(/\r?\n/)
				.filter((line) => line.startsWith('data:'))
				.map((line) => line.slice(5).trimStart())
				.join('\n')
				.trim()
		)
		.filter((data) => data && data !== '[DONE]')
		.map((data) => JSON.parse(data));

const readNextSseJsonEvent = async (
	reader: ReadableStreamDefaultReader<Uint8Array>,
	timeoutMs = 1000
) => {
	const decoder = new TextDecoder();
	let buffer = '';

	const readWithTimeout = () =>
		Promise.race([
			reader.read(),
			new Promise<never>((_, reject) =>
				setTimeout(() => reject(new Error('Timed out waiting for SSE event.')), timeoutMs)
			)
		]);

	while (true) {
		const { value, done } = await readWithTimeout();
		if (done) {
			return null;
		}

		buffer += decoder.decode(value, { stream: true });
		const separatorIndex = buffer.search(/\r?\n\r?\n/);
		if (separatorIndex === -1) {
			continue;
		}

		const eventText = buffer.slice(0, separatorIndex);
		const dataText = eventText
			.split(/\r?\n/)
			.filter((line) => line.startsWith('data:'))
			.map((line) => line.slice(5).trimStart())
			.join('\n')
			.trim();

		if (!dataText || dataText === '[DONE]') {
			continue;
		}

		return JSON.parse(dataText);
	}
};

const baseProviderBody = () => ({
	model: 'local-model',
	messages: [{ role: 'user', content: 'calculate this' }],
	stream: false
});

const reviewDisabledBody = (body: Record<string, any> = {}) => ({
	...body,
	params: {
		...(body.params ?? {}),
		local_agent_review: false
	}
});

beforeEach(() => {
	vi.clearAllMocks();
	vi.mocked(supportsNativeAndroidTools).mockReturnValue(false);
	vi.mocked(supportsNativeWebSearch).mockReturnValue(false);
	vi.mocked(supportsNativeAgentTools).mockReturnValue(false);
	vi.mocked(listNativeAgentTools).mockResolvedValue([]);
	vi.mocked(beginNativeAgentKeepAlive).mockResolvedValue(true);
	vi.mocked(endNativeAgentKeepAlive).mockResolvedValue(true);
	vi.mocked(confirmNativeMailSend).mockResolvedValue(undefined);
	vi.mocked(getLocalSettings).mockResolvedValue({
		localWebSearch: {
			engine: 'auto',
			resultCount: 5,
			fetchPageCount: 3,
			maxPageChars: 12000,
			timeoutMs: 15000
		}
	} as any);
});

describe('requestLocalAgentChatCompletion', () => {
	it('executes campus queries in an ordinary Android chat without a workspace', async () => {
		vi.mocked(supportsNativeAgentTools).mockReturnValue(true);
		vi.mocked(listNativeAgentTools).mockResolvedValue([{
			type: 'function', requiresWorkspace: false,
			function: { name: 'agent_academic_homework', description: 'Campus homework', parameters: { type: 'object', properties: {} } }
		}]);
		vi.mocked(executeNativeAgentTool).mockResolvedValue({ output: { ok: true, data: { homework: { items: [] } } } });
		expect(shouldUseLocalAgentLoop({})).toBe(true);
		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (body: Record<string, any>) => {
			requests.push(body);
			return [providerJsonResponse(requests.length === 1 ? {
				role: 'assistant', content: null,
				tool_calls: [{ id: 'campus-1', type: 'function', function: { name: 'agent_academic_homework', arguments: '{}' } }]
			} : { role: 'assistant', content: 'No cached homework in the requested window.' }), new AbortController()] as [Response, AbortController];
		});
		await requestLocalAgentChatCompletion({ body: reviewDisabledBody(), providerBody: baseProviderBody(), requestProvider });
		expect(requests[0].tools.map((tool: any) => tool.function.name)).toContain('agent_academic_homework');
		expect(executeNativeAgentTool).toHaveBeenCalledWith({ workspaceId: '', toolName: 'agent_academic_homework', arguments: {} });
		expect(JSON.parse(requests[1].messages.at(-1).content).output.ok).toBe(true);
	});

	it.each(['agent_dynamic_lookup', 'agent_file_read'])(
		'uses the advertised required fields for %s',
		async (toolName) => {
			vi.mocked(supportsNativeAgentTools).mockReturnValue(true);
			vi.mocked(listNativeAgentTools).mockResolvedValue([
				{
					type: 'function',
					requiresWorkspace: true,
					function: {
						name: toolName,
						description: 'Lookup by the current schema.',
						parameters: {
							type: 'object',
							properties: { recordId: { type: 'string' }, enabled: { type: 'boolean' } },
							required: ['recordId', 'enabled']
						}
					}
				}
			]);
			vi.mocked(executeNativeAgentTool).mockResolvedValue({ output: { ok: true } });
			const requests: Record<string, any>[] = [];
			const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
				requests.push(structuredClone(providerBody));
				const message =
					requests.length <= 2
						? {
								role: 'assistant',
								content: null,
								tool_calls: [
									{
										id: `call_${requests.length}`,
										type: 'function',
										function: {
											name: toolName,
											arguments: JSON.stringify(
												requests.length === 1
													? { enabled: false }
													: { recordId: 'record-1', enabled: false }
											)
										}
									}
								]
							}
						: { role: 'assistant', content: 'Done.' };
				return [providerJsonResponse(message), new AbortController()] as [
					Response,
					AbortController
				];
			});
			await requestLocalAgentChatCompletion({
				body: reviewDisabledBody({ params: { agent_workspace_id: 'workspace-1' } }),
				providerBody: baseProviderBody(),
				requestProvider
			});
			expect(JSON.parse(requests[1].messages.at(-1).content).missing).toEqual(['recordId']);
			expect(executeNativeAgentTool).toHaveBeenCalledTimes(1);
			expect(executeNativeAgentTool).toHaveBeenCalledWith({
				workspaceId: 'workspace-1',
				toolName,
				arguments: { recordId: 'record-1', enabled: false }
			});
		}
	);

	it('uses the local agent loop when a native Agent workspace id is present', () => {
		expect(shouldUseLocalAgentLoop({ params: { agent_workspace_id: 'workspace-1' } })).toBe(true);
	});

	it('uses the local agent loop when code interpreter is enabled', () => {
		expect(shouldUseLocalAgentLoop({ features: { code_interpreter: true } })).toBe(true);
	});

	it('starts and stops native keep-alive around a successful local Agent request', async () => {
		vi.mocked(supportsNativeAgentTools).mockReturnValue(true);
		const requestProvider = vi.fn(async () => [
			providerJsonResponse({
				role: 'assistant',
				content: 'Done.'
			}),
			new AbortController()
		] as [Response, AbortController]);

		const [res] = await requestLocalAgentChatCompletion({
			body: reviewDisabledBody(),
			providerBody: baseProviderBody(),
			requestProvider
		});
		await res?.json();

		expect(beginNativeAgentKeepAlive).toHaveBeenCalledTimes(1);
		const token = vi.mocked(beginNativeAgentKeepAlive).mock.calls[0][0].token;
		expect(vi.mocked(beginNativeAgentKeepAlive).mock.calls[0][0]).toMatchObject({
			token,
			reason: 'agent'
		});
		expect(endNativeAgentKeepAlive).toHaveBeenCalledTimes(1);
		expect(endNativeAgentKeepAlive).toHaveBeenCalledWith({ token });
	});

	it('stops native keep-alive when the provider throws', async () => {
		vi.mocked(supportsNativeAgentTools).mockReturnValue(true);
		const requestProvider = vi.fn(async () => {
			throw new Error('provider failed');
		});

		await expect(
			requestLocalAgentChatCompletion({
				body: reviewDisabledBody(),
				providerBody: baseProviderBody(),
				requestProvider
			})
		).rejects.toThrow('provider failed');

		const token = vi.mocked(beginNativeAgentKeepAlive).mock.calls[0][0].token;
		expect(endNativeAgentKeepAlive).toHaveBeenCalledWith({ token });
	});

	it('stops native keep-alive when a streaming local Agent request is aborted', async () => {
		vi.mocked(supportsNativeAgentTools).mockReturnValue(true);
		const stream = controlledProviderStreamResponse();
		const requestProvider = vi.fn(async () => [stream.response, new AbortController()] as [
			Response,
			AbortController
		]);

		const [res, controller] = await requestLocalAgentChatCompletion({
			body: reviewDisabledBody(),
			providerBody: { ...baseProviderBody(), stream: true },
			requestProvider
		});
		expect(res).toBeTruthy();

		controller.abort();
		await Promise.resolve();

		const token = vi.mocked(beginNativeAgentKeepAlive).mock.calls[0][0].token;
		expect(endNativeAgentKeepAlive).toHaveBeenCalledWith({ token });
		stream.done();
	});

	it('does not call native keep-alive bridge when native Agent tools are unavailable', async () => {
		const requestProvider = vi.fn(async () => [
			providerJsonResponse({
				role: 'assistant',
				content: 'Done.'
			}),
			new AbortController()
		] as [Response, AbortController]);

		const [res] = await requestLocalAgentChatCompletion({
			body: reviewDisabledBody(),
			providerBody: baseProviderBody(),
			requestProvider
		});
		await res?.json();

		expect(beginNativeAgentKeepAlive).not.toHaveBeenCalled();
		expect(endNativeAgentKeepAlive).not.toHaveBeenCalled();
	});

	it('injects native tools and loops after a provider tool call', async () => {
		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);

			if (requests.length === 1) {
				return [
					providerJsonResponse({
						role: 'assistant',
						content: null,
						tool_calls: [
							{
								id: 'call_1',
								type: 'function',
								function: {
									name: 'calculate_expression',
									arguments: '{"expression":"2 + 2"}'
								}
							}
						]
					}),
					new AbortController()
				] as [Response, AbortController];
			}

			return [
				providerJsonResponse({
					role: 'assistant',
					content: 'The result is 4.'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		const [res] = await requestLocalAgentChatCompletion({
			body: reviewDisabledBody(),
			providerBody: baseProviderBody(),
			requestProvider
		});
		const final = await res?.json();

		expect(requestProvider).toHaveBeenCalledTimes(2);
		expect(requests[0].tools).toEqual(LOCAL_AGENT_TOOLS);
		expect(requests[0].tool_choice).toBe('auto');
		expect(requests[1].messages.at(-2)).toMatchObject({
			role: 'assistant',
			tool_calls: expect.any(Array)
		});
		expect(requests[1].messages.at(-1)).toMatchObject({
			role: 'tool',
			tool_call_id: 'call_1',
			name: 'calculate_expression'
		});
		expect(JSON.parse(requests[1].messages.at(-1).content).result).toBe(4);
		const content = final.choices[0].message.content;
		expect(content).toContain('<details type="tool_calls" done="true"');
		expect(content).toContain('name="calculate_expression"');
		expect(content).toContain('expression');
		expect(content).toContain('result');
		expect(content).toContain('The result is 4.');
	});

	it('keeps a workspace Agent loop running past the web search tool budget', async () => {
		vi.mocked(supportsNativeWebSearch).mockReturnValue(true);
		vi.mocked(supportsNativeAgentTools).mockReturnValue(true);
		vi.mocked(listNativeAgentTools).mockResolvedValue([
			{
				type: 'function',
				function: {
					name: 'agent_file_read',
					description: 'Read a workspace file.',
					parameters: {
						type: 'object',
						properties: { path: { type: 'string' } },
						required: ['path'],
						additionalProperties: false
					}
				}
			},
			{
				type: 'function',
				function: {
					name: 'agent_file_write',
					description: 'Write a workspace file.',
					parameters: {
						type: 'object',
						properties: {
							path: { type: 'string' },
							content: { type: 'string' }
						},
						required: ['path', 'content'],
						additionalProperties: false
					}
				}
			}
		]);
		vi.mocked(executeNativeAgentTool).mockImplementation(async ({ toolName, arguments: args }) => ({
			output:
				toolName === 'agent_file_write'
					? { ok: true, path: args.path, size: String(args.content ?? '').length }
					: { ok: true, path: args.path, content: 'attachment text' }
		}));

		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);
			if (requests.length === 1) {
				return [
					providerJsonResponse({
						role: 'assistant',
						content: null,
						tool_calls: Array.from({ length: 6 }, (_, index) => ({
							id: `call_read_${index}`,
							type: 'function',
							function: {
								name: 'agent_file_read',
								arguments: JSON.stringify({ path: `work/attachment-${index}.txt` })
							}
						}))
					}),
					new AbortController()
				] as [Response, AbortController];
			}
			if (requests.length === 2) {
				return [
					providerJsonResponse({
						role: 'assistant',
						content: null,
						tool_calls: [
							{
								id: 'call_write',
								type: 'function',
								function: {
									name: 'agent_file_write',
									arguments: JSON.stringify({
										path: 'output/answer.md',
										content: 'Complete answer'
									})
								}
							}
						]
					}),
					new AbortController()
				] as [Response, AbortController];
			}

			return [
				providerJsonResponse({ role: 'assistant', content: 'Deliverable created.' }),
				new AbortController()
			] as [Response, AbortController];
		});

		const [res] = await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({
				features: { web_search: true },
				params: { agent_workspace_id: 'workspace-1', function_calling: 'native' }
			}),
			providerBody: baseProviderBody(),
			requestProvider
		});
		const final = await res?.json();

		expect(requestProvider).toHaveBeenCalledTimes(3);
		expect(executeNativeAgentTool).toHaveBeenCalledTimes(7);
		expect(executeNativeAgentTool).toHaveBeenCalledWith({
			workspaceId: 'workspace-1',
			toolName: 'agent_file_write',
			arguments: { path: 'output/answer.md', content: 'Complete answer' }
		});
		expect(searchWeb).not.toHaveBeenCalled();
		expect(final.choices[0].message.content).toContain('Deliverable created.');
	});

	it('passes provider reasoning back with assistant tool-call turns', async () => {
		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(structuredClone(providerBody));

			if (requests.length === 1) {
				return [
					providerJsonResponse({
						role: 'assistant',
						content: null,
						reasoning_content: 'I should calculate this before answering.',
						tool_calls: [
							{
								id: 'call_reasoning',
								type: 'function',
								function: {
									name: 'calculate_expression',
									arguments: '{"expression":"6 * 7"}'
								}
							}
						]
					}),
					new AbortController()
				] as [Response, AbortController];
			}

			return [
				providerJsonResponse({
					role: 'assistant',
					content: 'The result is 42.'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		const [res] = await requestLocalAgentChatCompletion({
			body: reviewDisabledBody(),
			providerBody: baseProviderBody(),
			requestProvider
		});
		const final = await res?.json();

		expect(requestProvider).toHaveBeenCalledTimes(2);
		expect(requests[1].messages.at(-2)).toMatchObject({
			role: 'assistant',
			content: null,
			reasoning_content: 'I should calculate this before answering.',
			tool_calls: expect.any(Array)
		});
		expect(JSON.parse(requests[1].messages.at(-1).content).result).toBe(42);
		expect(final.choices[0].message.content).not.toContain(
			'I should calculate this before answering.'
		);
		expect(final.choices[0].message.content).toContain('The result is 42.');
	});

	it('exposes the Android location tool when user location sharing is enabled', async () => {
		vi.mocked(supportsNativeAndroidTools).mockReturnValue(true);
		vi.mocked(getLocalSettings).mockResolvedValue({
			userLocation: true,
			localWebSearch: {
				engine: 'auto',
				resultCount: 5,
				fetchPageCount: 3,
				maxPageChars: 12000,
				timeoutMs: 15000
			}
		} as any);

		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);
			return [
				providerJsonResponse({
					role: 'assistant',
					content: 'Ready.'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({ features: { android_device_tools: true } }),
			providerBody: baseProviderBody(),
			requestProvider
		});

		const toolNames = requests[0].tools.map((tool: any) => tool.function.name);
		expect(toolNames).toContain('get_current_location');
	});

	it('hides the Android location tool when user location sharing is disabled', async () => {
		vi.mocked(supportsNativeAndroidTools).mockReturnValue(true);
		vi.mocked(getLocalSettings).mockResolvedValue({
			userLocation: false,
			localWebSearch: {
				engine: 'auto',
				resultCount: 5,
				fetchPageCount: 3,
				maxPageChars: 12000,
				timeoutMs: 15000
			}
		} as any);

		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);
			return [
				providerJsonResponse({
					role: 'assistant',
					content: 'Ready.'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({ features: { android_device_tools: true } }),
			providerBody: baseProviderBody(),
			requestProvider
		});

		const toolNames = requests[0].tools.map((tool: any) => tool.function.name);
		expect(toolNames).not.toContain('get_current_location');
	});

	it('exposes web search and Android location tools together when both features are enabled', async () => {
		vi.mocked(supportsNativeAndroidTools).mockReturnValue(true);
		vi.mocked(supportsNativeWebSearch).mockReturnValue(true);
		vi.mocked(getLocalSettings).mockResolvedValue({
			userLocation: true,
			localWebSearch: {
				engine: 'auto',
				resultCount: 5,
				fetchPageCount: 3,
				maxPageChars: 12000,
				timeoutMs: 15000
			}
		} as any);

		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);
			return [
				providerJsonResponse({
					role: 'assistant',
					content: 'Ready.'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({ features: { web_search: true, android_device_tools: true } }),
			providerBody: baseProviderBody(),
			requestProvider
		});

		const toolNames = requests[0].tools.map((tool: any) => tool.function.name);
		expect(toolNames).toContain('search_web');
		expect(toolNames).toContain('get_current_location');
	});

	it('turns malformed tool arguments into a tool error result and continues', async () => {
		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);

			if (requests.length === 1) {
				return [
					providerJsonResponse({
						role: 'assistant',
						content: null,
						tool_calls: [
							{
								id: 'call_bad_args',
								type: 'function',
								function: {
									name: 'calculate_expression',
									arguments: '{bad'
								}
							}
						]
					}),
					new AbortController()
				] as [Response, AbortController];
			}

			return [
				providerJsonResponse({
					role: 'assistant',
					content: 'Recovered after the tool error.'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		const [res] = await requestLocalAgentChatCompletion({
			body: reviewDisabledBody(),
			providerBody: baseProviderBody(),
			requestProvider
		});
		const final = await res?.json();
		const toolResult = JSON.parse(requests[1].messages.at(-1).content);

		expect(requestProvider).toHaveBeenCalledTimes(2);
		expect(toolResult.error).toContain('Tool call arguments are invalid JSON');
		expect(toolResult.tool).toBe('calculate_expression');
		const content = final.choices[0].message.content;
		expect(content).toContain('<details type="tool_calls" done="true"');
		expect(content).toContain('name="calculate_expression"');
		expect(content).toContain('Tool call arguments are invalid JSON');
		expect(content).toContain('Recovered after the tool error.');
	});

	it('stops runaway tool loops at the configured retry limit', async () => {
		const requestProvider = vi.fn(async () => {
			return [
				providerJsonResponse({
					role: 'assistant',
					content: null,
					tool_calls: [
						{
							id: 'call_loop',
							type: 'function',
							function: {
								name: 'get_current_timestamp',
								arguments: '{}'
							}
						}
					]
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		const [res] = await requestLocalAgentChatCompletion({
			body: reviewDisabledBody(),
			providerBody: baseProviderBody(),
			requestProvider,
			maxRetries: 1
		});
		const final = await res?.json();

		expect(requestProvider).toHaveBeenCalledTimes(2);
		expect(final.choices[0].message.content).toContain('maximum tool call retry limit');
	});

	it('preserves non-stream provider reasoning in the rendered message', async () => {
		const requestProvider = vi.fn(async () => {
			return [
				providerJsonResponse({
					role: 'assistant',
					reasoning_content: 'I can answer directly.',
					content: 'Direct answer.'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		const [res] = await requestLocalAgentChatCompletion({
			body: reviewDisabledBody(),
			providerBody: baseProviderBody(),
			requestProvider
		});
		const final = await res?.json();
		const content = final.choices[0].message.content;

		expect(content).toContain('<details type="reasoning" done="true"');
		expect(content).toContain('I can answer directly.');
		expect(content).toContain('Direct answer.');
		expect(final.choices[0].message).not.toHaveProperty('reasoning_content');
	});

	it('does not review final answers when local Agent review is disabled', async () => {
		const requestProvider = vi.fn(async () => [
			providerJsonResponse({
				role: 'assistant',
				content: 'Direct final answer.'
			}),
			new AbortController()
		] as [Response, AbortController]);

		const [res] = await requestLocalAgentChatCompletion({
			body: { params: { local_agent_review: false } },
			providerBody: baseProviderBody(),
			requestProvider
		});
		const final = await res?.json();

		expect(requestProvider).toHaveBeenCalledTimes(1);
		expect(final.choices[0].message.content).toContain('Direct final answer.');
	});

	it('does not meta-review clean workspace Agent final answers', async () => {
		const requestProvider = vi.fn(async () => [
			providerJsonResponse({
				role: 'assistant',
				content:
					'作业已完成，结果位于 `output/results.zip`。\n\n```makefile\nall: main\n```\n\n不会自动提交作业。'
			}),
			new AbortController()
		] as [Response, AbortController]);

		const [res] = await requestLocalAgentChatCompletion({
			body: { params: { agent_workspace_id: 'workspace-1' } },
			providerBody: baseProviderBody(),
			requestProvider
		});
		const final = await res?.json();

		expect(requestProvider).toHaveBeenCalledTimes(1);
		expect(final.choices[0].message.content).toContain('output/results.zip');
		expect(final.choices[0].message.content).not.toContain('internal tool protocol text');
	});

	it('repairs final answers rejected by the default local Agent review', async () => {
		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);

			if (requests.length === 1) {
				return [
					providerJsonResponse({
						role: 'assistant',
						content: 'Draft answer.'
					}),
					new AbortController()
				] as [Response, AbortController];
			}

			if (requests.length === 2) {
				return [
					providerJsonResponse({
						role: 'assistant',
						content: '{"approved":false,"reason":"review rejected draft"}'
					}),
					new AbortController()
				] as [Response, AbortController];
			}

			if (requests.length === 3) {
				return [
					providerJsonResponse({
						role: 'assistant',
						content: 'Repaired final answer.'
					}),
					new AbortController()
				] as [Response, AbortController];
			}

			return [
				providerJsonResponse({
					role: 'assistant',
					content: '{"approved":true,"reason":""}'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		const [res] = await requestLocalAgentChatCompletion({
			body: {},
			providerBody: baseProviderBody(),
			requestProvider
		});
		const final = await res?.json();

		expect(requestProvider).toHaveBeenCalledTimes(4);
		expect(requests[1]).toMatchObject({
			stream: false,
			temperature: 0
		});
		expect(requests[1]).not.toHaveProperty('tools');
		expect(requests[1]).not.toHaveProperty('tool_choice');
		expect(requests[2].messages.at(-1).content).toContain('review rejected draft');
		expect(final.choices[0].message.content).toContain('Repaired final answer.');
		expect(final.choices[0].message.content).not.toContain('Draft answer.');
	});

	it('does not stream provider reasoning before the provider turn is validated', async () => {
		const controlled = controlledProviderStreamResponse();
		const requestProvider = vi.fn(
			async () => [controlled.response, new AbortController()] as [Response, AbortController]
		);

		const [res] = await requestLocalAgentChatCompletion({
			body: reviewDisabledBody(),
			providerBody: { ...baseProviderBody(), stream: true },
			requestProvider
		});

		const reader = res?.body?.getReader();
		expect(reader).toBeTruthy();

		controlled.send({
			choices: [
				{
					index: 0,
					delta: {
						reasoning_content: 'Still planning before any tool call.'
					}
				}
			]
		});

		await expect(readNextSseJsonEvent(reader!, 50)).rejects.toThrow(
			'Timed out waiting for SSE event.'
		);

		controlled.done();
		await reader?.cancel().catch(() => undefined);
	});

	it('parses streamed tool call deltas and returns a normal SSE response', async () => {
		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);

			if (requests.length === 1) {
				return [
					providerStreamResponse([
						{
							choices: [
								{
									index: 0,
									delta: {
										reasoning_content: 'I should use the arithmetic tool.'
									}
								}
							]
						},
						{
							choices: [
								{
									index: 0,
									delta: {
										tool_calls: [
											{
												index: 0,
												id: 'call_stream',
												type: 'function',
												function: { name: 'calculate_expression', arguments: '{"expression":"' }
											}
										]
									}
								}
							]
						},
						{
							choices: [
								{
									index: 0,
									delta: {
										tool_calls: [
											{
												index: 0,
												function: { arguments: '3 * 3"}' }
											}
										]
									}
								}
							]
						}
					]),
					new AbortController()
				] as [Response, AbortController];
			}

			if (requests.length === 2) {
				return [
					providerStreamResponse([
						{
							choices: [
								{
									index: 0,
									delta: {
										content: 'The result is 9.'
									}
								}
							]
						}
					]),
					new AbortController()
				] as [Response, AbortController];
			}

			return [
				providerJsonResponse({
					role: 'assistant',
					content: '{"approved":true,"reason":""}'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		const [res] = await requestLocalAgentChatCompletion({
			body: {},
			providerBody: { ...baseProviderBody(), stream: true },
			requestProvider
		});
		const streamText = await res?.text();
		const events = parseSseJsonEvents(streamText);
		const contentEvents = events.filter((event) => typeof event.content === 'string');
		const finalDeltaText = events
			.map((event) => event.choices?.[0]?.delta?.content ?? '')
			.join('');

		expect(requestProvider).toHaveBeenCalledTimes(3);
		expect(JSON.parse(requests[1].messages.at(-1).content).result).toBe(9);
		const executingToolIndex = contentEvents.findIndex(
			(event) =>
				event.content.includes('<details type="tool_calls" done="false"') &&
				event.content.includes('name="calculate_expression"')
		);
		const completedToolIndex = contentEvents.findIndex((event) =>
			event.content.includes('<details type="tool_calls" done="true"')
		);
		const finalAnswerIndex = contentEvents.findIndex((event) =>
			event.content.includes('The result is 9.')
		);
		expect(executingToolIndex).toBeGreaterThanOrEqual(0);
		expect(completedToolIndex).toBeGreaterThan(executingToolIndex);
		expect(contentEvents[completedToolIndex].content).toContain('result');
		expect(finalAnswerIndex).toBe(-1);
		expect(finalDeltaText.startsWith('\n')).toBe(true);
		expect(finalDeltaText).toContain('The result is 9.');
		expect(streamText).not.toContain('I should use the arithmetic tool.');
		expect(streamText).toContain('data: [DONE]');
	});

	it('executes native Android web search tool calls and returns sources', async () => {
		vi.mocked(supportsNativeWebSearch).mockReturnValue(true);
		vi.mocked(searchWeb).mockResolvedValue([
			{
				title: 'Example result',
				url: 'https://example.com/news',
				snippet: 'Fresh result',
				content: 'Fetched page text',
				fetched: true
			}
		]);

		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);

			if (requests.length === 1) {
				return [
					providerJsonResponse({
						role: 'assistant',
						content: null,
						tool_calls: [
							{
								id: 'call_search',
								type: 'function',
								function: {
									name: 'search_web',
									arguments: '{"query":"latest android news"}'
								}
							}
						]
					}),
					new AbortController()
				] as [Response, AbortController];
			}

			return [
				providerJsonResponse({
					role: 'assistant',
					content: 'Here is the current result.'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		const [res] = await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({ features: { web_search: true } }),
			providerBody: baseProviderBody(),
			requestProvider
		});
		const final = await res?.json();

		expect(requestProvider).toHaveBeenCalledTimes(2);
		expect(requests[0].tools.map((tool: any) => tool.function.name)).toContain('search_web');
		expect(searchWeb).toHaveBeenCalledWith(
			expect.objectContaining({ query: 'latest android news', resultCount: 5 })
		);
		expect(JSON.parse(requests[1].messages.at(-1).content).results[0]).toMatchObject({
			url: 'https://example.com/news'
		});
		expect(final.sources[0].source.url).toBe('https://example.com/news');
		expect(final.statuses.some((status: any) => status.action === 'web_search')).toBe(true);
		const content = final.choices[0].message.content;
		expect(content).toContain('<details type="tool_calls" done="true"');
		expect(content).toContain('name="search_web"');
		expect(content).toContain('latest android news');
		expect(content).toContain('Here is the current result.');
	});

	it('keeps math tools available when web search is enabled and returns exact large integer results', async () => {
		vi.mocked(supportsNativeWebSearch).mockReturnValue(true);

		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);

			if (requests.length === 1) {
				return [
					providerJsonResponse({
						role: 'assistant',
						content: null,
						tool_calls: [
							{
								id: 'call_big_math',
								type: 'function',
								function: {
									name: 'calculate_expression',
									arguments: '{"expression":"38288274637×37377282828838"}'
								}
							}
						]
					}),
					new AbortController()
				] as [Response, AbortController];
			}

			return [
				providerJsonResponse({
					role: 'assistant',
					content: 'The exact result is 1431111670135373607581806.'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		const [res] = await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({ features: { web_search: true } }),
			providerBody: {
				...baseProviderBody(),
				messages: [{ role: 'user', content: '计算 38288274637×37377282828838' }]
			},
			requestProvider
		});
		const final = await res?.json();
		const toolNames = requests[0].tools.map((tool: any) => tool.function.name);
		const toolResult = JSON.parse(requests[1].messages.at(-1).content);

		expect(toolNames).toContain('search_web');
		expect(toolNames).toContain('calculate_expression');
		expect(searchWeb).not.toHaveBeenCalled();
		expect(toolResult.result).toBe('1431111670135373607581806');
		expect(final.choices[0].message.content).toContain('1431111670135373607581806');
	});

	it('exposes code interpreter tools without Android device tools when code interpreter is enabled', async () => {
		vi.mocked(supportsNativeAndroidTools).mockReturnValue(true);

		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);
			return [
				providerJsonResponse({
					role: 'assistant',
					content: 'Ready.'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({ features: { code_interpreter: true } }),
			providerBody: baseProviderBody(),
			requestProvider
		});

		const toolNames = requests[0].tools.map((tool: any) => tool.function.name);
		expect(toolNames).toContain('execute_python');
		expect(toolNames).toContain('calculate_expression');
		expect(toolNames).not.toContain('get_device_context');
		expect(toolNames).not.toContain('get_current_location');
	});

	it('exposes web search and code interpreter tools together when both features are enabled', async () => {
		vi.mocked(supportsNativeWebSearch).mockReturnValue(true);

		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);
			return [
				providerJsonResponse({
					role: 'assistant',
					content: 'Ready.'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({ features: { web_search: true, code_interpreter: true } }),
			providerBody: baseProviderBody(),
			requestProvider
		});

		const toolNames = requests[0].tools.map((tool: any) => tool.function.name);
		expect(toolNames).toContain('search_web');
		expect(toolNames).toContain('fetch_url');
		expect(toolNames).toContain('execute_python');
		expect(toolNames).toContain('calculate_expression');
	});

	it('injects native Agent workspace tools when a workspace id is present', async () => {
		vi.mocked(supportsNativeAgentTools).mockReturnValue(true);
		vi.mocked(listNativeAgentTools).mockResolvedValue([
			{
				type: 'function',
				function: {
					name: 'agent_file_list',
					description: 'List files in the native Agent workspace.',
					parameters: {
						type: 'object',
						properties: {
							path: { type: 'string' },
							recursive: { type: 'boolean' }
						},
						required: ['path'],
						additionalProperties: false
					}
				}
			},
			{
				type: 'function',
				requiresWorkspace: true,
				function: {
					name: 'agent_archive_extract',
					description: 'Extract a supported archive in the native Agent workspace.',
					parameters: {
						type: 'object',
						properties: {
							archivePath: { type: 'string' },
							targetDir: { type: 'string' }
						},
						required: ['archivePath', 'targetDir'],
						additionalProperties: false
					}
				}
			}
		]);
		vi.mocked(executeNativeAgentTool).mockResolvedValue({
			output: { ok: true, files: [{ path: 'inbox/task.pdf' }] }
		});

		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);

			if (requests.length === 1) {
				return [
					providerJsonResponse({
						role: 'assistant',
						content: null,
						tool_calls: [
							{
								id: 'call_agent_files',
								type: 'function',
								function: {
									name: 'agent_file_list',
									arguments: '{"path":"inbox"}'
								}
							}
						]
					}),
					new AbortController()
				] as [Response, AbortController];
			}

			return [
				providerJsonResponse({
					role: 'assistant',
					content: 'Found inbox/task.pdf.'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({ params: { agent_workspace_id: 'workspace-1' } }),
			providerBody: baseProviderBody(),
			requestProvider
		});

		expect(requests[0].tools.map((tool: any) => tool.function.name)).toContain('agent_file_list');
		expect(requests[0].tools.map((tool: any) => tool.function.name)).toContain('agent_archive_extract');
		expect(executeNativeAgentTool).toHaveBeenCalledWith({
			workspaceId: 'workspace-1',
			toolName: 'agent_file_list',
			arguments: { path: 'inbox' }
		});
		expect(JSON.parse(requests[1].messages.at(-1).content).output.files[0].path).toBe(
			'inbox/task.pdf'
		);
	});

	it('normalizes object native Agent tool arguments before execution', async () => {
		vi.mocked(supportsNativeAgentTools).mockReturnValue(true);
		vi.mocked(listNativeAgentTools).mockResolvedValue([
			{
				type: 'function',
				requiresWorkspace: true,
				function: {
					name: 'agent_archive_extract',
					description: 'Extract a supported archive in the native Agent workspace.',
					parameters: {
						type: 'object',
						properties: {
							archivePath: { type: 'string' },
							targetDir: { type: 'string' }
						},
						required: ['archivePath', 'targetDir'],
						additionalProperties: false
					}
				}
			}
		]);
		vi.mocked(executeNativeAgentTool).mockResolvedValue({
			output: { ok: true, files: [{ path: 'work/attachments/homework/task.md' }] }
		});

		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);

			if (requests.length === 1) {
				return [
					providerJsonResponse({
						role: 'assistant',
						content: null,
						tool_calls: [
							{
								id: 'call_extract',
								type: 'function',
								function: {
									name: 'agent_archive_extract',
									arguments: {
										archive_path: 'inbox/homework.zip',
										target_dir: 'work/attachments/homework'
									}
								}
							}
						]
					}),
					new AbortController()
				] as [Response, AbortController];
			}

			return [
				providerJsonResponse({
					role: 'assistant',
					content: 'Extracted.'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({ params: { agent_workspace_id: 'workspace-1' } }),
			providerBody: baseProviderBody(),
			requestProvider
		});

		expect(executeNativeAgentTool).toHaveBeenCalledWith({
			workspaceId: 'workspace-1',
			toolName: 'agent_archive_extract',
			arguments: {
				archivePath: 'inbox/homework.zip',
				targetDir: 'work/attachments/homework'
			}
		});
		const replayedToolCall = requests[1].messages.at(-2).tool_calls[0];
		expect(typeof replayedToolCall.function.arguments).toBe('string');
		expect(JSON.parse(replayedToolCall.function.arguments).archive_path).toBe(
			'inbox/homework.zip'
		);
	});

	it('normalizes common native Agent argument aliases across tools', async () => {
		vi.mocked(supportsNativeAgentTools).mockReturnValue(true);
		vi.mocked(listNativeAgentTools).mockResolvedValue([
			{
				type: 'function',
				function: {
					name: 'agent_document_extract_pdf',
					description: 'Extract a PDF.',
					parameters: {
						type: 'object',
						properties: {
							path: { type: 'string' },
							outputPath: { type: 'string' }
						},
						required: ['path', 'outputPath'],
						additionalProperties: false
					}
				}
			},
			{
				type: 'function',
				function: {
					name: 'agent_run_javascript',
					description: 'Run JavaScript.',
					parameters: {
						type: 'object',
						properties: {
							code: { type: 'string' },
							timeoutSeconds: { type: 'integer' }
						},
						required: ['code'],
						additionalProperties: false
					}
				}
			},
			{
				type: 'function',
				function: {
					name: 'agent_package_results',
					description: 'Package results.',
					parameters: {
						type: 'object',
						properties: {
							finalAnswer: { type: 'string' }
						},
						additionalProperties: false
					}
				}
			}
		]);
		vi.mocked(executeNativeAgentTool).mockResolvedValue({ output: { ok: true } });

		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);
			if (requests.length === 1) {
				return [
					providerJsonResponse({
						role: 'assistant',
						content: null,
						tool_calls: [
							{
								id: 'call_pdf',
								type: 'function',
								function: {
									name: 'agent_document_extract_pdf',
									arguments: '{"filename":"inbox/task.pdf","output_path":"work/task.md"}'
								}
							},
							{
								id: 'call_js',
								type: 'function',
								function: {
									name: 'agent_run_javascript',
									arguments: '{"code":"return input.value + 1;","timeout_ms":5000}'
								}
							},
							{
								id: 'call_package',
								type: 'function',
								function: {
									name: 'agent_package_results',
									arguments: '{"final_answer":"Done."}'
								}
							}
						]
					}),
					new AbortController()
				] as [Response, AbortController];
			}

			return [
				providerJsonResponse({
					role: 'assistant',
					content: 'Done.'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({ params: { agent_workspace_id: 'workspace-1' } }),
			providerBody: baseProviderBody(),
			requestProvider
		});

		expect(executeNativeAgentTool).toHaveBeenCalledWith({
			workspaceId: 'workspace-1',
			toolName: 'agent_document_extract_pdf',
			arguments: {
				outputPath: 'work/task.md',
				path: 'inbox/task.pdf'
			}
		});
		expect(executeNativeAgentTool).toHaveBeenCalledWith({
			workspaceId: 'workspace-1',
			toolName: 'agent_run_javascript',
			arguments: {
				code: 'return input.value + 1;',
				timeoutSeconds: 5
			}
		});
		expect(executeNativeAgentTool).toHaveBeenCalledWith({
			workspaceId: 'workspace-1',
			toolName: 'agent_package_results',
			arguments: {
				finalAnswer: 'Done.'
			}
		});
	});

	it('injects only non-workspace native Agent mail tools without a workspace id', async () => {
		vi.mocked(supportsNativeAgentTools).mockReturnValue(true);
		vi.mocked(listNativeAgentTools).mockResolvedValue([
			{
				type: 'function',
				requiresWorkspace: true,
				function: {
					name: 'agent_file_list',
					description: 'List files in the native Agent workspace.',
					parameters: {
						type: 'object',
						properties: {
							path: { type: 'string' },
							recursive: { type: 'boolean' }
						},
						required: ['path'],
						additionalProperties: false
					}
				}
			},
			{
				type: 'function',
				requiresWorkspace: false,
				function: {
					name: 'agent_mail_read',
					description: 'Read a mail message.',
					parameters: {
						type: 'object',
						properties: {
							message_id: { type: 'string' }
						},
						required: ['message_id'],
						additionalProperties: false
					}
				}
			}
		]);

		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);
			return [
				providerJsonResponse({
					role: 'assistant',
					content: 'Ready.'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({ params: { function_calling: 'native' } }),
			providerBody: baseProviderBody(),
			requestProvider
		});

		const toolNames = requests[0].tools.map((tool: any) => tool.function.name);
		expect(toolNames).toContain('agent_mail_read');
		expect(toolNames).not.toContain('agent_file_list');
	});

	it('does not execute agent_mail_send when the user denies confirmation', async () => {
		vi.mocked(supportsNativeAgentTools).mockReturnValue(true);
		vi.mocked(listNativeAgentTools).mockResolvedValue([{
			type: 'function', requiresWorkspace: false,
			function: { name: 'agent_mail_send', description: 'Send mail', parameters: { type: 'object' } }
		}]);
		vi.mocked(confirmNativeMailSend).mockRejectedValue(new Error('user_denied'));
		const args = {
			to: ['student@example.edu'],
			cc: ['teacher@example.edu'],
			bcc: [],
			subject: 'Draft subject',
			body: 'Draft body',
			is_html: false
		};

		const result = JSON.parse(await executeLocalTool('agent_mail_send', args));

		expect(confirmNativeMailSend).toHaveBeenCalledWith({
			to: ['student@example.edu'],
			cc: ['teacher@example.edu'],
			bcc: [],
			subject: 'Draft subject',
			body: 'Draft body',
			isHtml: false,
			attachmentCount: 0
		});
		expect(executeNativeAgentTool).not.toHaveBeenCalled();
		expect(result.error).toBe('user_denied');
	});

	it('executes agent_mail_send only after confirmation', async () => {
		vi.mocked(supportsNativeAgentTools).mockReturnValue(true);
		vi.mocked(listNativeAgentTools).mockResolvedValue([{
			type: 'function', requiresWorkspace: false,
			function: { name: 'agent_mail_send', description: 'Send mail', parameters: { type: 'object' } }
		}]);
		vi.mocked(executeNativeAgentTool).mockResolvedValue({
			output: { ok: true, sent: { sent_message_id: 'sent-1', compose_id: 'compose-1' } }
		});
		const args = {
			to: ['student@example.edu'],
			subject: 'Draft subject',
			body: 'Draft body',
			is_html: true
		};

		const result = JSON.parse(await executeLocalTool('agent_mail_send', args));

		expect(confirmNativeMailSend).toHaveBeenCalledWith({
			to: ['student@example.edu'],
			cc: [],
			bcc: [],
			subject: 'Draft subject',
			body: 'Draft body',
			isHtml: true,
			attachmentCount: 0
		});
		expect(executeNativeAgentTool).toHaveBeenCalledWith({
			workspaceId: '',
			toolName: 'agent_mail_send',
			arguments: args
		});
		expect(result.output.sent.sent_message_id).toBe('sent-1');
	});

	it('executes inline XML native Agent tool calls returned as message text', async () => {
		vi.mocked(supportsNativeAgentTools).mockReturnValue(true);
		vi.mocked(listNativeAgentTools).mockResolvedValue([
			{
				type: 'function',
				function: {
					name: 'agent_file_read',
					description: 'Read files in the native Agent workspace.',
					parameters: {
						type: 'object',
						properties: {
							path: { type: 'string' }
						},
						required: ['path'],
						additionalProperties: false
					}
				}
			}
		]);
		vi.mocked(executeNativeAgentTool).mockResolvedValue({
			output: {
				ok: true,
				path: 'inbox/makefile_example',
				content: 'makefile demo content',
				truncated: false,
				size_bytes: 21
			}
		});

		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);

			if (requests.length === 1) {
				return [
					providerJsonResponse({
						role: 'assistant',
						content:
							'好的，我来读取附件。\n\n<agent_file_read>\n<filename>inbox/makefile_example</filename>\n</agent_file_read>'
					}),
					new AbortController()
				] as [Response, AbortController];
			}

			return [
				providerJsonResponse({
					role: 'assistant',
					content: '附件内容是 makefile demo content。'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		const [res] = await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({ params: { agent_workspace_id: 'workspace-1' } }),
			providerBody: baseProviderBody(),
			requestProvider
		});
		const final = await res?.json();
		const toolResult = JSON.parse(requests[1].messages.at(-1).content);
		const finalContent = final.choices[0].message.content;

		expect(requests[0].tools.map((tool: any) => tool.function.name)).toContain(
			'agent_file_read'
		);
		expect(executeNativeAgentTool).toHaveBeenCalledWith({
			workspaceId: 'workspace-1',
			toolName: 'agent_file_read',
			arguments: { path: 'inbox/makefile_example' }
		});
		expect(toolResult.output.content).toBe('makefile demo content');
		expect(finalContent).toContain('<details type="tool_calls" done="true"');
		expect(finalContent).toContain('name="agent_file_read"');
		expect(finalContent).toContain('附件内容是 makefile demo content。');
		expect(finalContent).not.toContain('<agent_file_read>');
		expect(finalContent).not.toContain('<filename>inbox/makefile_example</filename>');
	});

	it('normalizes inline XML native Agent archive aliases', async () => {
		vi.mocked(supportsNativeAgentTools).mockReturnValue(true);
		vi.mocked(listNativeAgentTools).mockResolvedValue([
			{
				type: 'function',
				function: {
					name: 'agent_archive_extract',
					description: 'Extract archives in the native Agent workspace.',
					parameters: {
						type: 'object',
						properties: {
							archivePath: { type: 'string' },
							targetDir: { type: 'string' }
						},
						required: ['archivePath', 'targetDir'],
						additionalProperties: false
					}
				}
			}
		]);
		vi.mocked(executeNativeAgentTool).mockResolvedValue({
			output: { ok: true, files: [{ path: 'work/attachments/homework/task.md' }] }
		});

		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);
			if (requests.length === 1) {
				return [
					providerJsonResponse({
						role: 'assistant',
						content:
							'<agent_archive_extract>\n<file>inbox/homework.zip</file>\n<target_dir>work/attachments/homework</target_dir>\n</agent_archive_extract>'
					}),
					new AbortController()
				] as [Response, AbortController];
			}

			return [
				providerJsonResponse({
					role: 'assistant',
					content: 'Extracted.'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({ params: { agent_workspace_id: 'workspace-1' } }),
			providerBody: baseProviderBody(),
			requestProvider
		});

		expect(executeNativeAgentTool).toHaveBeenCalledWith({
			workspaceId: 'workspace-1',
			toolName: 'agent_archive_extract',
			arguments: {
				archivePath: 'inbox/homework.zip',
				targetDir: 'work/attachments/homework'
			}
		});
	});

	it('executes bare inline XML native Agent tool tags instead of finalizing as text', async () => {
		vi.mocked(supportsNativeAgentTools).mockReturnValue(true);
		vi.mocked(listNativeAgentTools).mockResolvedValue([
			{
				type: 'function',
				function: {
					name: 'agent_file_list',
					description: 'List files in the native Agent workspace.',
					parameters: {
						type: 'object',
						properties: {
							path: { type: 'string' },
							recursive: { type: 'boolean' }
						},
						required: ['path'],
						additionalProperties: false
					}
				}
			}
		]);
		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);

			if (requests.length === 1) {
				return [
					providerJsonResponse({
						role: 'assistant',
						content: '我先查看附件列表。\n\n<agent_file_list>'
					}),
					new AbortController()
				] as [Response, AbortController];
			}

			return [
				providerJsonResponse({
					role: 'assistant',
					content: '需要带上 path 参数，例如 inbox。'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		const [res] = await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({ params: { agent_workspace_id: 'workspace-1' } }),
			providerBody: baseProviderBody(),
			requestProvider
		});
		const final = await res?.json();
		const toolResult = JSON.parse(requests[1].messages.at(-1).content);
		const finalContent = final.choices[0].message.content;

		expect(requestProvider).toHaveBeenCalledTimes(2);
		expect(executeNativeAgentTool).not.toHaveBeenCalled();
		expect(toolResult).toMatchObject({
			tool: 'agent_file_list',
			missing: ['path'],
			expected_arguments_example: { path: 'inbox' }
		});
		expect(toolResult.error).toContain('Missing required argument');
		expect(finalContent).toContain('<details type="tool_calls" done="true"');
		expect(finalContent).toContain('name="agent_file_list"');
		expect(finalContent).toContain('需要带上 path 参数，例如 inbox。');
		expect(finalContent).not.toContain('<agent_file_list>');
	});

	it('executes self-closing inline XML native Agent tool tags', async () => {
		vi.mocked(supportsNativeAgentTools).mockReturnValue(true);
		vi.mocked(listNativeAgentTools).mockResolvedValue([
			{
				type: 'function',
				requiresWorkspace: false,
				function: {
					name: 'agent_mail_list_folders',
					description: 'List Coremail folders.',
					parameters: {
						type: 'object',
						properties: {},
						required: [],
						additionalProperties: false
					}
				}
			}
		]);
		vi.mocked(executeNativeAgentTool).mockResolvedValue({
			output: { ok: true, folders: [{ id: '1', name: 'Inbox' }] }
		});

		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);

			if (requests.length === 1) {
				return [
					providerJsonResponse({
						role: 'assistant',
						content: '<agent_mail_list_folders />'
					}),
					new AbortController()
				] as [Response, AbortController];
			}

			return [
				providerJsonResponse({
					role: 'assistant',
					content: 'Found the inbox folder.'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		const [res] = await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({ params: { function_calling: 'native' } }),
			providerBody: baseProviderBody(),
			requestProvider
		});
		const final = await res?.json();
		const finalContent = final.choices[0].message.content;

		expect(requestProvider).toHaveBeenCalledTimes(2);
		expect(executeNativeAgentTool).toHaveBeenCalledWith({
			workspaceId: '',
			toolName: 'agent_mail_list_folders',
			arguments: {}
		});
		expect(finalContent).toContain('name="agent_mail_list_folders"');
		expect(finalContent).toContain('Found the inbox folder.');
		expect(finalContent).not.toContain('<agent_mail_list_folders');
	});

	it('hides streamed inline XML native Agent tags from content snapshots', async () => {
		vi.mocked(supportsNativeAgentTools).mockReturnValue(true);
		vi.mocked(listNativeAgentTools).mockResolvedValue([
			{
				type: 'function',
				function: {
					name: 'agent_file_list',
					description: 'List files in the native Agent workspace.',
					parameters: {
						type: 'object',
						properties: {
							path: { type: 'string' },
							recursive: { type: 'boolean' }
						},
						required: ['path'],
						additionalProperties: false
					}
				}
			}
		]);
		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);

			if (requests.length === 1) {
				return [
					providerStreamResponse([
						{
							choices: [
								{
									index: 0,
									delta: {
										content: '我先查看附件列表。\n\n<agent_file_list>'
									}
								}
							]
						}
					]),
					new AbortController()
				] as [Response, AbortController];
			}

			return [
				providerStreamResponse([
					{
						choices: [
							{
								index: 0,
								delta: {
									content: '需要带上 path 参数，例如 inbox。'
								}
							}
						]
					}
				]),
				new AbortController()
			] as [Response, AbortController];
		});

		const [res] = await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({ params: { agent_workspace_id: 'workspace-1' } }),
			providerBody: { ...baseProviderBody(), stream: true },
			requestProvider
		});
		const streamText = await res?.text();
		const events = parseSseJsonEvents(streamText);
		const contentEvents = events.filter((event) => typeof event.content === 'string');
		const finalDeltaText = events
			.map((event) => event.choices?.[0]?.delta?.content ?? '')
			.join('');

		expect(requestProvider).toHaveBeenCalledTimes(2);
		expect(executeNativeAgentTool).not.toHaveBeenCalled();
		expect(streamText).toContain('expected_arguments_example');
		expect(streamText).toContain('missing');
		expect(streamText).not.toContain('<agent_file_list');
		expect(streamText).not.toContain('我先查看附件列表。');
		expect(contentEvents.some((event) => event.content.includes('name="agent_file_list"'))).toBe(
			true
		);
		expect(finalDeltaText).toContain('需要带上 path 参数，例如 inbox。');
		expect(streamText).toContain('data: [DONE]');
	});

	it('blocks streamed DSML tool protocol text and repairs without executing it', async () => {
		vi.mocked(supportsNativeAgentTools).mockReturnValue(true);
		vi.mocked(listNativeAgentTools).mockResolvedValue([
			{
				type: 'function',
				function: {
					name: 'agent_file_list',
					description: 'List files in the native Agent workspace.',
					parameters: {
						type: 'object',
						properties: {
							path: { type: 'string' }
						},
						required: ['path'],
						additionalProperties: false
					}
				}
			}
		]);

		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);

			if (requests.length === 1) {
				return [
					providerStreamResponse([
						{
							choices: [
								{
									index: 0,
									delta: {
										content:
											'先查看附件。\n\n<| | DSML | | tool_calls>\n<| | DSML | | invoke name="agent_file_list">\n<| | DSML | | parameter name="path" string="true">inbox</| | DSML | | parameter>\n</| | DSML | | invoke>\n</| | DSML | | tool_calls>'
									}
								}
							]
						}
					]),
					new AbortController()
				] as [Response, AbortController];
			}

			return [
				providerStreamResponse([
					{
						choices: [
							{
								index: 0,
								delta: {
									content: '我会重新用可见文本回答。'
								}
							}
						]
					}
				]),
				new AbortController()
			] as [Response, AbortController];
		});

		const [res] = await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({ params: { agent_workspace_id: 'workspace-1' } }),
			providerBody: { ...baseProviderBody(), stream: true },
			requestProvider
		});
		const streamText = await res?.text();
		const events = parseSseJsonEvents(streamText);
		const finalDeltaText = events
			.map((event) => event.choices?.[0]?.delta?.content ?? '')
			.join('');

		expect(requestProvider).toHaveBeenCalledTimes(2);
		expect(requests[1].messages.at(-1).content).toContain('Internal retry instruction');
		expect(executeNativeAgentTool).not.toHaveBeenCalled();
		expect(streamText).not.toContain('DSML');
		expect(streamText).not.toContain('invoke name=');
		expect(finalDeltaText).toContain('我会重新用可见文本回答。');
	});

	it('falls back to local RAG search when the model does not call web tools', async () => {
		vi.mocked(supportsNativeWebSearch).mockReturnValue(true);
		vi.mocked(searchWeb).mockResolvedValue([
			{
				title: 'Fallback result',
				url: 'https://example.com/fallback',
				snippet: 'Fallback snippet',
				content: 'Fallback page text',
				fetched: true
			}
		]);

		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);

			return [
				providerJsonResponse({
					role: 'assistant',
					content: requests.length === 1 ? 'Answer without search.' : 'Answer with fallback search.'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		const [res] = await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({ features: { web_search: true } }),
			providerBody: {
				...baseProviderBody(),
				messages: [{ role: 'user', content: 'What happened today?' }]
			},
			requestProvider
		});
		const final = await res?.json();

		expect(requestProvider).toHaveBeenCalledTimes(2);
		expect(searchWeb).toHaveBeenCalledWith(
			expect.objectContaining({ query: 'What happened today?' })
		);
		expect(requests[1].messages[0]).toMatchObject({
			role: 'system',
			content: expect.stringContaining('Fallback page text')
		});
		expect(final.sources[0].source.url).toBe('https://example.com/fallback');
		expect(final.choices[0].message.content).toBe('Answer with fallback search.');
	});

	it('falls back to local RAG search when provider rejects tool requests', async () => {
		vi.mocked(supportsNativeWebSearch).mockReturnValue(true);
		vi.mocked(searchWeb).mockResolvedValue([
			{
				title: 'Tool rejection result',
				url: 'https://example.com/rejected',
				snippet: 'Rejected tools snippet',
				content: 'Rejected tools page text',
				fetched: true
			}
		]);

		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);

			if (requests.length === 1) {
				return [new Response('tools are unsupported', { status: 400 }), new AbortController()] as [
					Response,
					AbortController
				];
			}

			return [
				providerJsonResponse({
					role: 'assistant',
					content: 'Recovered through fallback search.'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		const [res] = await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({ features: { web_search: true } }),
			providerBody: {
				...baseProviderBody(),
				messages: [{ role: 'user', content: 'Need current info' }]
			},
			requestProvider
		});
		const final = await res?.json();

		expect(requestProvider).toHaveBeenCalledTimes(2);
		expect(requests[1]).not.toHaveProperty('tools');
		expect(final.sources[0].source.url).toBe('https://example.com/rejected');
		expect(final.choices[0].message.content).toBe('Recovered through fallback search.');
	});

	it('retries web search fallback after a web tool connection failure', async () => {
		vi.mocked(supportsNativeWebSearch).mockReturnValue(true);
		vi.mocked(searchWeb)
			.mockRejectedValueOnce(new Error('failed to connect to lite.duckduckgo.com'))
			.mockResolvedValueOnce([
				{
					title: 'Retry result',
					url: 'https://example.com/retry',
					snippet: 'Retry snippet',
					content: 'Retry page text',
					fetched: true
				}
			]);

		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);

			if (requests.length === 1) {
				return [
					providerJsonResponse({
						role: 'assistant',
						content: null,
						tool_calls: [
							{
								id: 'call_search_failure',
								type: 'function',
								function: {
									name: 'search_web',
									arguments: '{"query":"38288274637 * 37377282828838"}'
								}
							}
						]
					}),
					new AbortController()
				] as [Response, AbortController];
			}

			return [
				providerJsonResponse({
					role: 'assistant',
					content: 'Answer with retry search context.'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		const [res] = await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({ features: { web_search: true } }),
			providerBody: {
				...baseProviderBody(),
				messages: [{ role: 'user', content: 'Need current info' }]
			},
			requestProvider
		});
		const final = await res?.json();

		expect(requestProvider).toHaveBeenCalledTimes(2);
		expect(searchWeb).toHaveBeenCalledTimes(2);
		expect(searchWeb).toHaveBeenNthCalledWith(
			1,
			expect.objectContaining({
				engine: 'auto',
				query: '38288274637 * 37377282828838'
			})
		);
		expect(searchWeb).toHaveBeenNthCalledWith(
			2,
			expect.objectContaining({
				engine: 'auto',
				query: 'Need current info'
			})
		);
		expect(requests[1].messages[0]).toMatchObject({
			role: 'system',
			content: expect.stringContaining('Retry page text')
		});
		expect(
			final.statuses.some((status: any) => status.description?.includes('retrying from DuckDuckGo'))
		).toBe(true);
		expect(final.sources[0].source.url).toBe('https://example.com/retry');
		expect(final.choices[0].message.content).toContain('name="search_web"');
		expect(final.choices[0].message.content).toContain('failed to connect to lite.duckduckgo.com');
		expect(final.choices[0].message.content).toContain('Answer with retry search context.');
	});

	it('streams fallback reasoning after retrying a web tool connection failure', async () => {
		vi.mocked(supportsNativeWebSearch).mockReturnValue(true);
		vi.mocked(searchWeb).mockRejectedValue(new Error('failed to connect to lite.duckduckgo.com'));

		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);

			if (requests.length === 1) {
				return [
					providerStreamResponse([
						{
							choices: [
								{
									index: 0,
									delta: {
										tool_calls: [
											{
												index: 0,
												id: 'call_search_failure',
												type: 'function',
												function: {
													name: 'search_web',
													arguments: '{"query":"blocked search"}'
												}
											}
										]
									}
								}
							]
						}
					]),
					new AbortController()
				] as [Response, AbortController];
			}

			return [
				providerStreamResponse([
					{
						choices: [
							{
								index: 0,
								delta: {
									reasoning_content: 'I will answer without web context.'
								}
							}
						]
					},
					{
						choices: [
							{
								index: 0,
								delta: {
									content: 'Fallback answer without web.'
								}
							}
						]
					}
				]),
				new AbortController()
			] as [Response, AbortController];
		});

		const [res] = await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({ features: { web_search: true } }),
			providerBody: {
				...baseProviderBody(),
				stream: true,
				messages: [{ role: 'user', content: 'Need current info' }]
			},
			requestProvider
		});
		const streamText = await res?.text();
		const events = parseSseJsonEvents(streamText);
		const contentEvents = events.filter((event) => typeof event.content === 'string');
		const finalDeltaText = events
			.map((event) => event.choices?.[0]?.delta?.content ?? '')
			.join('');

		expect(requestProvider).toHaveBeenCalledTimes(2);
		expect(searchWeb).toHaveBeenCalledTimes(2);
		const completedToolIndex = contentEvents.findIndex(
			(event) =>
				event.content.includes('<details type="tool_calls" done="true"') &&
				event.content.includes('failed to connect to lite.duckduckgo.com')
		);
		expect(completedToolIndex).toBeGreaterThanOrEqual(0);
		expect(contentEvents.some((event) => event.content.includes('I will answer without web context.'))).toBe(
			false
		);
		expect(finalDeltaText).toContain('<details type="reasoning" done="true"');
		expect(finalDeltaText).toContain('I will answer without web context.');
		expect(finalDeltaText).toContain('Fallback answer without web.');
	});

	it('continues with normal completion when local web search fails', async () => {
		vi.mocked(supportsNativeWebSearch).mockReturnValue(true);
		vi.mocked(searchWeb).mockRejectedValue(new Error('offline'));

		const requests: Record<string, any>[] = [];
		const requestProvider = vi.fn(async (providerBody: Record<string, any>) => {
			requests.push(providerBody);
			return [
				providerJsonResponse({
					role: 'assistant',
					content: requests.length === 1 ? 'No tool call.' : 'Normal answer after search failure.'
				}),
				new AbortController()
			] as [Response, AbortController];
		});

		const [res] = await requestLocalAgentChatCompletion({
			body: reviewDisabledBody({ features: { web_search: true } }),
			providerBody: {
				...baseProviderBody(),
				messages: [{ role: 'user', content: 'Need current info' }]
			},
			requestProvider
		});
		const final = await res?.json();

		expect(requestProvider).toHaveBeenCalledTimes(2);
		expect(searchWeb).toHaveBeenCalledTimes(1);
		expect(final.statuses.at(-1).description).toContain('Web search unavailable');
		expect(final.choices[0].message.content).toBe('Normal answer after search failure.');
	});
});

describe('local-first core tools', () => {
	it('executes Android current location through the native bridge', async () => {
		vi.mocked(getCurrentLocation).mockResolvedValue({
			latitude: 31.2304,
			longitude: 121.4737,
			accuracy: 20,
			provider: 'gps',
			time: 1760000000000,
			formatted: '31.230, 121.474 (lat, long)'
		});

		const result = JSON.parse(
			await executeLocalTool('get_current_location', {
				timeout_ms: 20000,
				high_accuracy: true
			})
		);

		expect(getCurrentLocation).toHaveBeenCalledWith({ timeoutMs: 20000, highAccuracy: true });
		expect(result).toMatchObject({
			latitude: 31.2304,
			longitude: 121.4737,
			formatted: '31.230, 121.474 (lat, long)'
		});
	});

	it('searches and views local file records through mocked IndexedDB data', async () => {
		vi.mocked(listLocalFiles).mockResolvedValue([
			{
				id: 'file-1',
				user_id: 'local-user',
				filename: 'notes.md',
				name: 'notes.md',
				type: 'text',
				size: 42,
				content_type: 'text/markdown',
				status: 'uploaded',
				created_at: 1,
				updated_at: 2,
				data: { content: 'budget planning notes' }
			}
		]);
		vi.mocked(getLocalFileRecord).mockResolvedValue({
			id: 'file-1',
			user_id: 'local-user',
			filename: 'notes.md',
			name: 'notes.md',
			type: 'text',
			size: 42,
			content_type: 'text/markdown',
			status: 'uploaded',
			created_at: 1,
			updated_at: 2,
			data: { content: 'budget planning notes' }
		});

		const search = JSON.parse(
			await executeLocalTool('search_local_files', { query: 'budget', limit: 5 })
		);
		const view = JSON.parse(await executeLocalTool('view_local_file', { id: 'file-1' }));

		expect(search.files).toHaveLength(1);
		expect(search.files[0]).toMatchObject({ id: 'file-1', filename: 'notes.md' });
		expect(view).toMatchObject({
			id: 'file-1',
			filename: 'notes.md',
			content: 'budget planning notes'
		});
	});

	it('searches and views local chat records through mocked IndexedDB data', async () => {
		const chat = {
			id: 'chat-1',
			user_id: 'local-user',
			title: 'Budget chat',
			chat: {
				history: {
					messages: {
						'1': { id: '1', role: 'user', content: 'Find the budget notes', timestamp: 1 },
						'2': { id: '2', role: 'assistant', content: 'They are in notes.md', timestamp: 2 }
					}
				}
			},
			created_at: 1,
			updated_at: 2
		};

		vi.mocked(listLocalChats).mockResolvedValue([chat]);
		vi.mocked(getLocalChat).mockResolvedValue(chat);

		const search = JSON.parse(
			await executeLocalTool('search_local_chats', { query: 'budget', limit: 5 })
		);
		const view = JSON.parse(await executeLocalTool('view_local_chat', { id: 'chat-1' }));

		expect(search.chats).toHaveLength(1);
		expect(search.chats[0]).toMatchObject({ id: 'chat-1', title: 'Budget chat' });
		expect(view.messages).toMatchObject([
			{ id: '1', role: 'user', content: 'Find the budget notes' },
			{ id: '2', role: 'assistant', content: 'They are in notes.md' }
		]);
	});
});
