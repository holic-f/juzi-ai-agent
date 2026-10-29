package com.yupi.juziaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 终止工具（作用是让自主规划智能体能够合理的终止）
 */
public class TerminateTool {
    @Tool(description = """
            End the task only when it is complete or cannot proceed. Prefer answering the user directly without this tool.
            If using this tool, include a concise finalAnswer based on already observed results to avoid another model request.
            Do not combine a prewritten finalAnswer with other tools whose results have not been observed yet.
            """)
    public String doTerminate(@ToolParam(required = false, description = "Final user-facing answer based only on verified tool results; omit if a summary is still needed") String finalAnswer) {
        return finalAnswer == null || finalAnswer.isBlank() ? "任务结束" : finalAnswer;
    }
}
