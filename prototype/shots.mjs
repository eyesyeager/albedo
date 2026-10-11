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

const clickTextFirst = async (text, opts = {}) => {
  const exact = !!opts.exact;
  const name = exact ? text : new RegExp(text);
  await page.getByRole("button", { name, exact }).first().click(opts);
};

const countCards = (sel) => page.evaluate((s) => document.querySelectorAll(s).length, sel);

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

  // 33 Skill 导入弹窗
  await go("/skills");
  await clickText("导入 Skill");
  await sleep(400);
  await shot("33-skills-import-modal");

  // 34 上传 SKILL.md 并展示解析报告（校验项 / frontmatter / 资源清单 / 内容预览）
  await page.setInputFiles(".pkg-input", "/tmp/albedo-demo-skill/SKILL.md");
  await sleep(700);
  await shot("34-skills-package-parsed");
  console.log("parse report rendered:", await countCards(".pkg-report"));

  // 35 由技能包导入生成的自定义 Skill
  await clickText("导入", { exact: true });
  await sleep(600);
  await shot("35-skills-package-imported");
  console.log("skill cards after package import:", await countCards(".skill-card"));

  // 36 Skill 删除确认
  await clickTextFirst("删除");
  await sleep(400);
  await shot("36-skills-delete-confirm");
  await clickText("确认删除", { exact: true });
  await sleep(500);
  console.log("skill cards after delete:", await countCards(".skill-card"));

  // 37 模型删除确认（模型不校验应用占用）
  await go("/models");
  const modelsBefore = await countCards(".model-card");
  await page.locator(".model-card .danger").first().click();
  await sleep(400);
  await shot("37-models-delete-confirm");
  await clickText("确认删除", { exact: true });
  await sleep(500);
  console.log("model cards:", modelsBefore, "->", await countCards(".model-card"));

  // 38 知识库被应用占用 → 禁止删除（kb-001 被 app-1001 挂载）
  await go("/knowledge");
  await shot("38-kb-usage-hint");
  await page.locator(".kb-card .danger").nth(0).click();
  await sleep(400);
  await shot("39-kb-delete-blocked");
  await clickText("我知道了", { exact: true });
  await sleep(400);
  console.log("kb cards after blocked attempt (expect 3):", await countCards(".kb-card"));

  // 40 知识库未被占用 → 允许删除（kb-003 法务合同库）
  await page.locator(".kb-card .danger").nth(2).click();
  await sleep(400);
  await shot("40-kb-delete-confirm");
  await clickText("确认删除", { exact: true });
  await sleep(500);
  console.log("kb cards after delete (expect 2):", await countCards(".kb-card"));

  // 41 MCP 被应用占用 → 禁止删除（mc-001 被 app-1001/app-1002 挂载）
  await go("/mcp");
  await shot("41-mcp-usage-hint");
  await page.locator(".mc-card .danger").nth(0).click();
  await sleep(400);
  await shot("42-mcp-delete-blocked");
  await clickText("我知道了", { exact: true });
  await sleep(400);
  console.log("mcp cards after blocked attempt (expect 3):", await countCards(".mc-card"));

  // 43 MCP 未被占用 → 允许删除（mc-003 天气服务）
  await page.locator(".mc-card .danger").nth(2).click();
  await sleep(400);
  await shot("43-mcp-delete-confirm");
  await clickText("确认删除", { exact: true });
  await sleep(500);
  console.log("mcp cards after delete (expect 2):", await countCards(".mc-card"));

  // 44 应用侧解绑工具后保存（释放被占用资源）
  await go("/apps/app-1001");
  const toolsBefore = await countCards(".tool-row");
  await page.locator(".tool-del").first().click();
  await sleep(300);
  await clickText("保存配置", { exact: true });
  await sleep(700);
  await shot("44-app-detail-unbind-save");
  console.log("tool rows:", toolsBefore, "->", await countCards(".tool-row"));

  // 45 健康检查进行中 + 46 LLM 响应结果 + 47 卡片检查结论
  await go("/models");
  await page.locator('.model-card button:has-text("健康检查")').first().click();
  await sleep(300);
  await shot("45-models-health-checking");
  await sleep(1400);
  await shot("46-models-health-ok");
  await clickText("关闭", { exact: true });
  await sleep(500);
  await shot("47-models-health-result-on-card");
  console.log("cards with health result:", await countCards(".hc-row"));

  // 48 健康检查失败（离线且未配置密钥的 Reranker）
  await page.locator('.model-card button:has-text("健康检查")').nth(5).click();
  await sleep(1600);
  await shot("48-models-health-fail");
  await clickText("关闭", { exact: true });
  await sleep(400);
  await shot("49-models-health-result-mixed");
  console.log("cards with health result:", await countCards(".hc-row"));

  // 50 点击 Skill 卡片进入解析详情页
  await go("/skills");
  await page.locator(".skill-card").first().click();
  await sleep(600);
  await shot("50-skills-detail-parse");
  console.log("detail url:", await page.evaluate(() => location.hash));

  // 51 包内文件：按解压后的文件夹树展示
  await clickText("包内文件");
  await sleep(400);
  await shot("51-skills-detail-files-tree");
  console.log("tree rows:", await countCards(".tree-row"));

  // 52 选中子目录内的文件查看内容
  await page.locator(".tree-row").last().click();
  await sleep(400);
  await shot("52-skills-detail-file-content");

  // 53 在文件夹中直接编辑入口文件（改 frontmatter 名称）+ 54 保存后元数据同步
  await page.locator(".editor").fill(
    "---\nname: 网页搜索（自定义派生）\ndescription: 检索最新网页信息，交叉验证后作答。\nversion: 1.1.0\nicon: 🔎\n---\n\n# 网页搜索\n\n## 使用约束\n- 至少 2 个独立来源交叉验证；\n- 给出来源链接与时间。"
  );
  await sleep(400);
  await shot("53-skills-detail-edit-in-folder");
  await clickText("保存", { exact: true });
  await sleep(700);
  await shot("54-skills-detail-after-save");
  console.log("skill name after save:", await page.evaluate(() => document.querySelector(".dh-name")?.textContent?.trim()));

  // 下载技能包导出（真实触发文件下载）
  const dlPromise = page.waitForEvent("download");
  await clickText("下载", { exact: true });
  const dl = await dlPromise;
  console.log("downloaded:", dl.suggestedFilename());

  // 56 压缩包技能包解析报告
  await go("/skills");
  await clickText("导入 Skill");
  await sleep(300);
  await page.setInputFiles(".pkg-input", "/tmp/albedo-demo-skill.zip");
  await sleep(1200);
  await shot("55-skills-package-zip-parsed");
  console.log("zip parse report rendered:", await countCards(".pkg-report"));
} catch (e) {
  console.error("ERR", e);
} finally {
  await browser.close();
}
