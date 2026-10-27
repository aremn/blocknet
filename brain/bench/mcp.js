// Use the production MCP implementation, with feedback/session logs isolated from the live brain.
import { dirname, join } from 'node:path';
import { Body } from '../body.js';
import { serveMcp } from '../mcp-server.js';

serveMcp(new Body(), 'BenchBot', process.stdin, process.stdout, {
  feedbackFile: join(dirname(process.env.BLOCKNET_AUDIT), 'feedback.jsonl'),
});
