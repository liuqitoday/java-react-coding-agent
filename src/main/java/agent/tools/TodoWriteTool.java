package agent.tools;

import agent.todo.TodoItem;
import agent.todo.TodoList;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * todo_write 工具：全量替换当前 Session 的 todo 列表。
 *
 * 设计定位——作为"外部工作记忆"：
 * - LLM 主动把自己的任务拆分写进 TodoList
 * - Agent 在每轮调用前把列表注入 system prompt，LLM 持续看到自己的计划
 * - 这个工具从根本上改变了 LLM 的规划行为：它开始"想清楚再做"
 *
 * 为什么单工具全量替换而不是 create/update 多工具？
 * - 演示直观：每次调用都能看到完整任务状态
 * - LLM 更简单：不需要记住每个任务的 id
 * - 代价仅是每次传全集的 token，对一般任务（< 10 个 todo）可忽略
 *
 * 约束：
 * - status 只能是 pending / in_progress / completed
 * - 最多只能有一个 in_progress（强制串行推进，避免"什么都在做"）
 */
public class TodoWriteTool implements Tool {

    private final TodoList todoList;

    public TodoWriteTool(TodoList todoList) {
        this.todoList = todoList;
    }

    @Override
    public String name() {
        return "todo_write";
    }

    @Override
    public String description() {
        return "Manage the task list for the current session by replacing the entire todo list in one call. "
                + "Use this to plan multi-step tasks and track progress. Each todo has: "
                + "content (imperative form, e.g., 'Create index.html'), "
                + "activeForm (present-continuous form shown while in progress, e.g., 'Creating index.html'), "
                + "and status (pending / in_progress / completed). "
                + "At most one todo may be in_progress at a time. "
                + "Mark a todo completed IMMEDIATELY after finishing it — do not batch completions.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject todosProp = new JsonObject();
        todosProp.addProperty("type", "array");
        todosProp.addProperty("description",
                "The complete list of todos for this session. Fully replaces the existing list.");

        // items schema: object with content / activeForm / status
        JsonObject itemSchema = new JsonObject();
        itemSchema.addProperty("type", "object");

        JsonObject itemProps = new JsonObject();

        JsonObject contentProp = new JsonObject();
        contentProp.addProperty("type", "string");
        contentProp.addProperty("description",
                "What needs to be done, in imperative form (e.g., 'Create index.html')");
        itemProps.add("content", contentProp);

        JsonObject activeFormProp = new JsonObject();
        activeFormProp.addProperty("type", "string");
        activeFormProp.addProperty("description",
                "Present-continuous form shown while the todo is in progress (e.g., 'Creating index.html')");
        itemProps.add("activeForm", activeFormProp);

        JsonObject statusProp = new JsonObject();
        statusProp.addProperty("type", "string");
        statusProp.addProperty("description", "One of: pending, in_progress, completed");
        JsonArray statusEnum = new JsonArray();
        statusEnum.add("pending");
        statusEnum.add("in_progress");
        statusEnum.add("completed");
        statusProp.add("enum", statusEnum);
        itemProps.add("status", statusProp);

        itemSchema.add("properties", itemProps);

        JsonArray itemRequired = new JsonArray();
        itemRequired.add("content");
        itemRequired.add("activeForm");
        itemRequired.add("status");
        itemSchema.add("required", itemRequired);

        todosProp.add("items", itemSchema);
        properties.add("todos", todosProp);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("todos");
        schema.add("required", required);

        return schema;
    }

    @Override
    public ToolResult execute(JsonObject args) {
        if (!args.has("todos") || !args.get("todos").isJsonArray()) {
            return ToolResult.error("todos 参数缺失或不是数组");
        }

        JsonArray arr = args.getAsJsonArray("todos");
        List<TodoItem> newItems = new ArrayList<>();
        int inProgressCount = 0;

        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (!el.isJsonObject()) {
                return ToolResult.error("第 " + (i + 1) + " 个 todo 不是对象");
            }
            JsonObject obj = el.getAsJsonObject();

            String content = readStringField(obj, "content");
            String activeForm = readStringField(obj, "activeForm");
            String status = readStringField(obj, "status");

            if (content == null || content.isBlank()) {
                return ToolResult.error("第 " + (i + 1) + " 个 todo 的 content 为空");
            }
            if (activeForm == null || activeForm.isBlank()) {
                return ToolResult.error("第 " + (i + 1) + " 个 todo 的 activeForm 为空");
            }
            if (status == null) {
                return ToolResult.error("第 " + (i + 1) + " 个 todo 的 status 缺失");
            }

            TodoItem item = new TodoItem(content, activeForm, status);
            if (!item.isValidStatus()) {
                return ToolResult.error("第 " + (i + 1) + " 个 todo 的 status 非法：" + status
                        + "。只能是 pending / in_progress / completed");
            }
            if (TodoItem.IN_PROGRESS.equals(status)) {
                inProgressCount++;
            }

            newItems.add(item);
        }

        if (inProgressCount > 1) {
            return ToolResult.error("至多只能有一个 in_progress 的 todo，当前有 " + inProgressCount
                    + " 个。请只把当前正在做的那一项设为 in_progress，其他保留 pending。");
        }

        // 全量替换
        todoList.replace(newItems);

        // 返回统计概览，让 LLM 确认更新成功并看到当前分布
        int pending = 0, inProgress = 0, completed = 0;
        for (TodoItem item : newItems) {
            switch (item.status()) {
                case TodoItem.PENDING -> pending++;
                case TodoItem.IN_PROGRESS -> inProgress++;
                case TodoItem.COMPLETED -> completed++;
            }
        }

        return ToolResult.success("Todo 列表已更新：共 " + newItems.size() + " 项（"
                + pending + " pending / " + inProgress + " in_progress / " + completed + " completed）");
    }

    /** 安全读取字符串字段：缺失或 null 返回 null，避免 getAsString() 抛异常。 */
    private String readStringField(JsonObject obj, String field) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) {
            return null;
        }
        return obj.get(field).getAsString();
    }
}
