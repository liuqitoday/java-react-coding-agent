package agent.tools;

import agent.ToolResult;
import agent.skills.SkillDescriptor;
import agent.skills.SkillLoader;
import agent.skills.SkillLoader.SkillContent;
import agent.skills.SkillRegistry;
import agent.skills.SkillSessionState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;

/**
 * activate_skill 工具：供 LLM 调用以激活一个 skill。
 *
 * 当 LLM 判断当前任务与某个 skill 的描述匹配时，调用此工具加载该 skill
 * 的完整指令（SKILL.md 正文）和可用资源列表。
 */
public class ActivateSkillTool implements Tool {

    private final SkillRegistry registry;
    private final SkillSessionState sessionState;
    private final SkillLoader loader;

    public ActivateSkillTool(SkillRegistry registry, SkillSessionState sessionState) {
        this.registry = registry;
        this.sessionState = sessionState;
        this.loader = new SkillLoader();
    }

    @Override
    public String name() {
        return "activate_skill";
    }

    @Override
    public String description() {
        return "激活一个 skill，加载其完整指令和可用资源。当任务与某个 skill 的描述匹配时调用此工具。";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        JsonObject nameProp = new JsonObject();
        nameProp.addProperty("type", "string");
        nameProp.addProperty("description", "要激活的 skill 名称");
        properties.add("name", nameProp);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("name");
        schema.add("required", required);

        return schema;
    }

    @Override
    public ToolResult execute(JsonObject args) {
        String skillName = args.get("name").getAsString();

        // 查找 skill
        SkillDescriptor descriptor = registry.get(skillName);
        if (descriptor == null) {
            return ToolResult.error("未找到 skill：" + skillName
                    + "。可用的 skills：" + availableSkillNames());
        }

        // 检查是否已激活
        if (sessionState.isActive(skillName)) {
            return ToolResult.success("skill \"" + skillName + "\" 已处于激活状态，无需重复加载。");
        }

        // 加载 skill 内容
        SkillContent content;
        try {
            content = loader.load(descriptor);
        } catch (IOException e) {
            return ToolResult.error("加载 skill 失败：" + e.getMessage());
        }

        // 标记为激活
        sessionState.activate(skillName);

        // 组装返回内容
        StringBuilder result = new StringBuilder();
        result.append("已激活 skill: ").append(skillName).append("\n");
        result.append("来源: ").append(descriptor.rootPath()).append("\n");
        result.append("\n--- Skill 指令 ---\n\n");
        result.append(content.body());

        if (!content.resourceSummary().isEmpty()) {
            result.append("\n\n--- 可用资源 ---\n\n");
            result.append(content.resourceSummary());
        }

        return ToolResult.success(result.toString());
    }

    private String availableSkillNames() {
        StringBuilder sb = new StringBuilder();
        for (SkillDescriptor desc : registry.getAll()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(desc.name());
        }
        return sb.toString();
    }
}
