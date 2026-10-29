//package com.yupi.juziaiagent.demo.invoke;
//
//import dev.langchain4j.community.model.dashscope.QwenChatModel;
//import dev.langchain4j.model.chat.ChatModel;
//
//public class LangChainAiInvoke {
//
//    public static void main(String[] args) {
//        ChatModel qwenModel = QwenChatModel.builder()
//                .apiKey(TestApiKey.API_KEY)
//                .modelName("qwen-max")
//                .build();
//        String answer = qwenModel.chat("我是程序员鱼皮，这是编程导航 codefather.cn 的原创项目教程");
//        System.out.println(answer);
//    }
//}
