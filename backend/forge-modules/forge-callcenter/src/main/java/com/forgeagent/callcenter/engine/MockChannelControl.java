package com.forgeagent.callcenter.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 通道控制 Mock(C1 / V-5):内存记录全部命令轨迹,供单测断言「引擎发出了哪些命令」;
 * 无可编程事件回调需求时即够用——通话事件(挂机/坐席状态)由 fixture 经事件链路注入,
 * 与命令轨迹天然解耦(命令是下行、事件是上行)。
 */
public class MockChannelControl implements ChannelControl {

    /** 命令轨迹元素:op + 关键参数,断言用。 */
    public record Command(String op, String uuid, String arg, int payloadBytes, long atMs) {
    }

    private final String uuid;
    private final long startMs;
    private final List<Command> commands = new ArrayList<>();
    private byte[] lastPlayback;
    private boolean playing;
    private boolean answered;
    private boolean hungUp;

    public MockChannelControl(String uuid, long startMs) {
        this.uuid = uuid;
        this.startMs = startMs;
    }

    @Override
    public String uuid() {
        return uuid;
    }

    @Override
    public void answer() {
        answered = true;
        commands.add(new Command("answer", uuid, null, 0, now()));
    }

    @Override
    public void playAudio(byte[] pcm8k) {
        lastPlayback = pcm8k;
        playing = true;
        commands.add(new Command("play", uuid, null, pcm8k == null ? 0 : pcm8k.length, now()));
    }

    @Override
    public void interruptPlayback() {
        playing = false;
        commands.add(new Command("interrupt", uuid, null, 0, now()));
    }

    @Override
    public void transferToQueue(String queueName) {
        commands.add(new Command("transfer", uuid, queueName, 0, now()));
    }

    @Override
    public void startRecording(String objectKey) {
        commands.add(new Command("record", uuid, objectKey, 0, now()));
    }

    @Override
    public void hangup(String cause) {
        hungUp = true;
        commands.add(new Command("hangup", uuid, cause, 0, now()));
    }

    // ---- 断言辅助 ----

    public List<Command> commands() {
        return List.copyOf(commands);
    }

    /** 按 op 过滤轨迹。 */
    public List<Command> commandsOf(String op) {
        return commands.stream().filter(c -> c.op().equals(op)).toList();
    }

    public boolean isAnswered() {
        return answered;
    }

    public boolean isHungUp() {
        return hungUp;
    }

    public boolean isPlaying() {
        return playing;
    }

    public byte[] lastPlayback() {
        return lastPlayback;
    }

    private long now() {
        return System.currentTimeMillis() - startMs;
    }
}
