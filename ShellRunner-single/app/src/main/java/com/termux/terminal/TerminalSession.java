package com.termux.terminal;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 基于 JNI 原生 PTY 的终端会话。
 * 使用 posix_openpt + fork + exec 创建真实 PTY，替代 Java ProcessBuilder 管道方案。
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(4096);
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    private final byte[] mUtf8InputBuffer = new byte[5];

    TerminalSessionClient mClient;

    int mShellPid;
    int mShellExitStatus;
    public String mSessionName;

    final Handler mMainThreadHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_NEW_INPUT:
                    if (mEmulator == null) break;
                    byte[] buffer = new byte[4096];
                    int bytesRead = mProcessToTerminalIOQueue.read(buffer, false);
                    while (bytesRead > 0) {
                        mEmulator.append(buffer, bytesRead);
                        // 通知 UI 刷新
                        if (mClient != null) mClient.onTextChanged(TerminalSession.this);
                        bytesRead = mProcessToTerminalIOQueue.read(buffer, false);
                    }
                    break;
                case MSG_PROCESS_EXITED:
                    mShellPid = -1;
                    mRunning = false;
                    mShellExitStatus = msg.arg1;
                    if (mClient != null) mClient.onSessionFinished(TerminalSession.this);
                    break;
            }
        }
    };

    /** 当不为空时，终端启动后自动执行此脚本，然后进入交互式 shell */
    public String mScriptPath = null;
    /** 执行模式：0=交互模式(默认)、1=sh执行、2=cd执行 */
    public int mScriptMode = 0;

    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;
    private final Integer mTranscriptRows;

    // ===== PTY 管理 =====
    /** Master PTY file descriptor (from JNI) */
    private int mMasterFd = -1;
    /** Child process PID */
    private int mChildPid = -1;
    private FileInputStream mTermInput;
    private FileOutputStream mTermOutput;
    private Thread mReaderThread;
    private Thread mWriterThread;
    private volatile boolean mRunning;

    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(String shellPath, String cwd, String[] args, String[] env,
                           Integer transcriptRows, TerminalSessionClient client) {
        // shellPath is unused for PTY mode (we always start su)
        this.mCwd = cwd;
        this.mArgs = args;
        this.mEnv = env;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
            // Notify PTY of window size change via TIOCSWINSZ
            if (mMasterFd >= 0 && mRunning) {
                setPtyWindowSize(columns, rows, cellWidthPixels, cellHeightPixels);
            }
        }
    }

    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
        startProcess(columns, rows);
    }

    // ===== Native PTY helpers =====

    private void setPtyWindowSize(int columns, int rows, int widthPx, int heightPx) {
        try {
            JNI.setPtyWindowSize(mMasterFd, rows, columns, widthPx, heightPx);
        } catch (UnsatisfiedLinkError e) {
            // fallback: send stty command through PTY
            if (mTermOutput != null) {
                try {
                    String cmd = String.format("stty rows %d cols %d\n", rows, columns);
                    mTermOutput.write(cmd.getBytes(StandardCharsets.UTF_8));
                    mTermOutput.flush();
                } catch (IOException ignored) {}
            }
        }
    }

    private void startProcess(int columns, int rows) {
        try {
            // ===== 使用 JNI 创建真实 PTY =====
            int[] pidOut = new int[1];
            mMasterFd = JNI.createSubprocess("su", mCwd, pidOut);
            if (mMasterFd < 0) throw new IOException("PTY creation failed");

            mChildPid = pidOut[0];

            // 将 raw fd 转为 Java FileDescriptor
            FileDescriptor fd = new FileDescriptor();
            try {
                Field f = FileDescriptor.class.getDeclaredField("descriptor");
                f.setAccessible(true);
                f.setInt(fd, mMasterFd);
            } catch (Exception e) {
                throw new IOException("Failed to wrap PTY fd: " + e.getMessage());
            }

            mTermInput = new FileInputStream(fd);
            mTermOutput = new FileOutputStream(fd);

            // 设置 PTY 初始大小
            setPtyWindowSize(columns, rows, 0, 0);

            // 发送初始 shell 配置（通过 PTY 发送）
            // su 已经启动在 PTY 中，发送 setup 命令使 shell 进入交互模式
            String setupCmds = "cd '" + mCwd.replace("'", "'\\''") + "' 2>/dev/null\n" +
                "export TERM=xterm-256color\n" +
                "export LANG=en_US.UTF-8\n" +
                "export PATH=/system/bin:/system/xbin:/sbin:/vendor/bin:/data/adb/magisk:$PATH\n" +
                "exec sh -i\n";
            mTermOutput.write(setupCmds.getBytes(StandardCharsets.UTF_8));
            mTermOutput.flush();

            mRunning = true;
            mShellPid = mChildPid;

            // ===== 读取线程：PTY master → ByteQueue → TerminalEmulator =====
            // 注意：不使用 waitPid 检测进程退出，因为 su 可能 fork 子进程后立即退出。
            // 改为监控 PTY master 的 EOF：当所有附属进程关闭 slave 时，read() 返回 -1，
            // 此时才真正意味 shell 已退出。
            mReaderThread = new Thread("TermSessionInputReader") {
                @Override
                public void run() {
                    byte[] buffer = new byte[4096];
                    try {
                        while (mRunning) {
                            int read = mTermInput.read(buffer, 0, buffer.length);
                            if (read <= 0) break;
                            if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) break;
                            mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                        }
                    } catch (IOException ignored) {}
                    // PTY EOF 或错误 → shell 进程已退出
                    // 通过 synchronized 防止与 finishIfRunning() 竞态
                    synchronized (TerminalSession.this) {
                        if (mRunning) {
                            mRunning = false;
                            // waitpid 返回立即（进程已退出）或阻塞（还在运行）
                            // 如果还在运行（su fork 场景），这里会阻塞直到 shell 真正退出
                            int exitCode = (mChildPid > 0) ? JNI.waitPid(mChildPid) : -1;
                            mMainThreadHandler.sendMessage(
                                mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, exitCode, 0));
                        }
                    }
                }
            };
            mReaderThread.setDaemon(true);
            mReaderThread.start();

            // ===== 写入线程：ByteQueue → PTY master =====
            mWriterThread = new Thread("TermSessionOutputWriter") {
                @Override
                public void run() {
                    byte[] buffer = new byte[4096];
                    try {
                        while (mRunning) {
                            int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                            if (bytesToWrite == -1) break;
                            mTermOutput.write(buffer, 0, bytesToWrite);
                            mTermOutput.flush();
                        }
                    } catch (IOException ignored) {}
                }
            };
            mWriterThread.setDaemon(true);
            mWriterThread.start();

        } catch (Exception e) {
            mShellPid = -1;
            mShellExitStatus = -1;
            mRunning = false;
            if (mEmulator != null) {
                mEmulator.append(("启动失败: " + e.getMessage() + "\n").getBytes(StandardCharsets.UTF_8),
                    ("启动失败: " + e.getMessage() + "\n").getBytes(StandardCharsets.UTF_8).length);
            }
        }
    }

    @Override
    public void write(byte[] data, int offset, int count) {
        if (mShellPid > 0) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /**
     * 将文本直接写入终端 emulator 的输出 buffer（不经过 PTY/进程）
     * 用于 session 结束后显示退出码等提示信息
     */
    public void feedText(String text) {
        if (mEmulator != null) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            mEmulator.append(bytes, bytes.length);
        }
    }

    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            return;
        }
        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;
        if (codePoint <= 0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= 0b11111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= 0b1111111111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= 0b111111111111111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    public void reset() {
        finishIfRunning();
        mProcessToTerminalIOQueue.clear();
        mTerminalToProcessIOQueue.clear();
    }

    public void finishIfRunning() {
        synchronized (this) {
            if (!mRunning) return;
            mRunning = false;
            mShellPid = -1;
        }
        // 关闭 PTY 并终止子进程
        if (mMasterFd >= 0 || mChildPid > 0) {
            JNI.closePty(mMasterFd, mChildPid);
            mMasterFd = -1;
            mChildPid = -1;
        }
        try { if (mTermInput != null) mTermInput.close(); } catch (Exception ignored) {}
        try { if (mTermOutput != null) mTermOutput.close(); } catch (Exception ignored) {}
    }

    public synchronized boolean isRunning() {
        return mRunning && mShellPid > 0;
    }

    public synchronized int getExitStatus() {
        return mShellExitStatus;
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {}

    @Override
    public void onCopyTextToClipboard(String text) {
        if (mClient != null) mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        if (mClient != null) mClient.onPasteTextFromClipboard(this);
    }

    public void onBell() {}

    public void onColorsChanged() {}

    public int getPid() {
        return mShellPid;
    }

    public String getCwd() {
        return mCwd;
    }

    void notifyScreenUpdate() {}

}
