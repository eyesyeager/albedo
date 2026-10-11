import { chromium } from "playwright";
import { mkdirSync } from "node:fs";

const BASE = "http://localhost:5173";
const DIR = "/Users/yearton/Documents/project/albedo/prototype/screenshots";
mkdirSync(DIR, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await ctx.newPage();

const shot = async (name) => {
  await sleep(350);
  await page.screenshot({ path: `${DIR}/${name}.png`, fullPage: true });
  console.log("saved", name);
};

let navN = 0;
const go = async (hash) => {
  navN++;
  await page.goto(`${BASE}/?cb=${navN}#${hash}`, { waitUntil: "load" });
  await sleep(550);
};

const clickText = async (text, opts = {}) => {
  const exact = !!opts.exact;
  const name = exact ? text : new RegExp(text);
  await page.getByRole("button", { name, exact }).click(opts);
};

try {
  // 01 登录
  await go("/login");
  await shot("01-login");

  // 登录进入主框架
  await clickText("登录并授权");
  await sleep(900);
  await shot("02-overview");

  // 03 应用列表
  await go("/apps");
  await shot("03-apps-list");

  // 04 应用创建弹窗
  await clickText("创建应用");
  await sleep(400);
  await shot("04-apps-create-modal");

  // 05 应用详情-智能体配置
  await go("/apps/app-1001");
  await shot("05-app-detail-config");

  // 06 应用详情-调试台
  await clickText("调试台");
  await sleep(300);
  await shot("06-app-detail-debug");

  // 07 应用详情-调试台运行中（发送后展示 trace）
  await clickText("发送");
  await sleep(2800);
  await shot("07-app-detail-debug-running");

  // 08 应用详情-AGUI API
  await clickText("AGUI API");
  await sleep(300);
  await shot("08-app-detail-api");

  // 09 AGUI API-显示密钥
  await clickText("显示", { timeout: 3000 }).catch(() => {});
  await sleep(300);
  await shot("09-app-detail-api-showkey");

  // 10 AGUI API-Node.js 示例
  await clickText("Node.js");
  await sleep(300);
  await shot("10-app-detail-api-node");

  // 11 应用详情-会话回放
  await clickText("会话回放");
  await sleep(300);
  await shot("11-app-detail-sessions");

  // 12 应用详情-快照回滚
  await clickText("快照回滚");
  await sleep(300);
  await shot("12-app-detail-snap");

  // 13 模型列表
  await go("/models");
  await shot("13-models-list");

  // 14 模型列表-Reranker 筛选
  await clickText("Reranker");
  await sleep(300);
  await shot("14-models-filter-reranker");

  // 15 接入模型弹窗（LLM 表单）
  await go("/models");
  await sleep(200);
  await clickText("接入模型");
  await sleep(400);
  await shot("15-models-create-modal");

  // 16 接入模型弹窗-Reranker 提供商选择
  await page.selectOption(".modal select", "RERANKER");
  await sleep(400);
  await shot("16-models-create-reranker");

  // 17 知识库列表
  await go("/knowledge");
  await shot("17-knowledge-list");

  // 18 新建知识库弹窗
  await clickText("新建知识库");
  await sleep(400);
  await shot("18-kb-create-modal");

  // 19 知识库详情-文档管理
  await go("/knowledge/kb-001");
  await shot("19-kb-detail-docs");

  // 20 知识库详情-检索测试
  await clickText("检索测试", { exact: true });
  await sleep(300);
  await shot("20-kb-detail-retrieve");

  // 21 知识库详情-检索结果
  await clickText("执行检索测试", { exact: true });
  await sleep(1200);
  await shot("21-kb-detail-retrieve-results");

  // 22 知识库详情-切片与检索配置
  await clickText("切片与检索配置");
  await sleep(300);
  await shot("22-kb-detail-config");

  // 23 上传文档弹窗
  await clickText("上传文档");
  await sleep(400);
  await shot("23-kb-detail-upload-modal");

  // 24 知识库详情-索引中状态
  await go("/knowledge/kb-003");
  await shot("24-kb-detail-indexing");

  // 25 MCP 列表
  await go("/mcp");
  await shot("25-mcp-list");

  // 26 接入 MCP 弹窗
  await clickText("接入 MCP");
  await sleep(400);
  await shot("26-mcp-create-modal");

  // 27 MCP 详情-已连接
  await go("/mcp/mc-001");
  await shot("27-mcp-detail-connected");

  // 28 MCP 详情-连通性测试日志
  await clickText("连通性测试");
  await sleep(2800);
  await shot("28-mcp-detail-testing");

  // 29 MCP 详情-连接异常
  await go("/mcp/mc-003");
  await shot("29-mcp-detail-error");

  // 30 Skill 列表
  await go("/skills");
  await shot("30-skills-list");

  // 31 操作日志列表
  await go("/logs");
  await shot("31-logs-list");

  // 32 操作日志-失败筛选
  await clickText("失败");
  await sleep(400);
  await shot("32-logs-filter-fail");
} catch (e) {
  console.error("ERR", e);
} finally {
  await browser.close();
}
