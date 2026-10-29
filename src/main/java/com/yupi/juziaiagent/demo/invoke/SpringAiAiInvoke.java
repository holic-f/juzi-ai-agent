package com.yupi.juziaiagent.demo.invoke;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.AssistantMessage;
// import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

// 原说明：取消注释即可在 SpringBoot 项目启动时执行
// 新写法：通过 app.ai.demo-enabled 控制是否在启动时执行
@Component
// 默认不在启动时调用付费模型；需要演示时设置 app.ai.demo-enabled=true
@ConditionalOnProperty(name = "app.ai.demo-enabled", havingValue = "true")
public class SpringAiAiInvoke implements CommandLineRunner {

    // 原写法：@Resource
    // private ChatModel dashscopeChatModel;
    @Resource
    private OpenAiChatModel openAiChatModel;

    @Override
    public void run(String... args) throws Exception {
        // 原写法：AssistantMessage output = dashscopeChatModel.call(new Prompt("你好，我是橘子"))
        AssistantMessage output = openAiChatModel.call(new Prompt("你好，我是橘子"))
                .getResult()
                .getOutput();
        System.out.println(output.getText());
    }
}
