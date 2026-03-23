package agent.skills;

import java.util.HashSet;
import java.util.Set;

/**
 * 会话级 Skill 激活状态管理。
 *
 * 记录当前会话中已激活的 skill 名称，用于：
 * - 避免重复激活同一 skill
 * - 在 system prompt 中展示已激活状态
 */
public class SkillSessionState {

    private final Set<String> activeSkills = new HashSet<>();

    /** 标记一个 skill 为已激活。返回 true 表示新激活，false 表示已存在。 */
    public boolean activate(String name) {
        return activeSkills.add(name);
    }

    /** 检查 skill 是否已激活。 */
    public boolean isActive(String name) {
        return activeSkills.contains(name);
    }

    /** 获取所有已激活的 skill 名称。 */
    public Set<String> getActiveSkills() {
        return Set.copyOf(activeSkills);
    }

    /** 已激活的 skill 数量。 */
    public int activeCount() {
        return activeSkills.size();
    }
}
