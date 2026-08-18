package com.eyes.albedo.skill.dto;

/**
 * {@code skill_exec} 工具的脚本资源解析结果（{@code SkillInjectionService.loadExecutableScript}
 * 的产出，随 {@code AiMessage.tool} 间接回灌模型 —— 实际执行发生在
 * {@code tool/handler/SkillExecHandler}，本类只负责"取出哪份脚本、什么语言、正文是什么"）。
 *
 * <p>🔴 {@code content} 就是 {@code skill_resources.content} 的快照（已完成变量替换），
 * 与 {@code SkillLoadResult} 同源纪律：<b>来自平台/租户预置数据，不接受模型/用户运行时
 * 传入的任意代码</b>。{@code language} 取自 {@code skill_resources.content_type}，
 * 由 {@code SkillExecHandler} 按白名单映射到具体解释器，非白名单语言一律拒绝执行。
 *
 * @param skillKey     技能键（回显）
 * @param resourcePath 资源路径（回显，对应 {@code skill_resources.resource_path}）
 * @param language     资源语言标记（{@code skill_resources.content_type}，如 {@code python}/{@code shell}）
 * @param content      已完成变量替换的脚本正文
 */
public record SkillScriptResult(String skillKey, String resourcePath, String language, String content) {
}
