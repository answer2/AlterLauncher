//
// Created by Aanswer_Dev on 2025/2/2.
//

#include "OpenGLRenderer.h"
#include <GLES2/gl2.h>
#include <EGL/egl.h>
#include <android/log.h>

#define LOG_TAG "OpenGLTest"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

OpenGLRenderer::OpenGLRenderer(EGLDisplay display, EGLSurface surface, EGLContext context)
        : dpy(display), surface(surface), eglContext(context), vertexShaderCode(R"(
        attribute vec4 vPosition;
        void main() {
            gl_Position = vPosition;
        }
    )"), fragmentShaderCode(R"(
        precision mediump float;
        uniform vec4 uColor;
        void main() {
            gl_FragColor = uColor;
        }
    )") {

    vertices[0] = -0.5f; vertices[1] =  0.5f; vertices[2] = 0.0f; // 左上角
    vertices[3] = -0.5f; vertices[4] = -0.5f; vertices[5] = 0.0f; // 左下角
    vertices[6] =  0.5f; vertices[7] =  0.5f; vertices[8] = 0.0f; // 右上角
    vertices[9] =  0.5f; vertices[10] = -0.5f; vertices[11] = 0.0f; // 右下角

    color[0] = 1.0f; color[1] = 0.0f; color[2] = 0.0f; color[3] = 1.0f; // 红色

    InitOpenGL();
}

OpenGLRenderer::~OpenGLRenderer() {
    CleanupOpenGL();
}

void OpenGLRenderer::InitOpenGL() {
    // 编译顶点着色器
    GLuint vertexShader = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(vertexShader, 1, &vertexShaderCode, NULL);
    glCompileShader(vertexShader);

    // 编译片段着色器
    GLuint fragmentShader = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(fragmentShader, 1, &fragmentShaderCode, NULL);
    glCompileShader(fragmentShader);

    // 创建程序并链接
    program = glCreateProgram();
    glAttachShader(program, vertexShader);
    glAttachShader(program, fragmentShader);
    glLinkProgram(program);
    glUseProgram(program);

    // 获取顶点位置和颜色的句柄
    positionHandle = glGetAttribLocation(program, "vPosition");
    colorHandle = glGetUniformLocation(program, "uColor");

    // 创建并绑定VBO
    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);

    // 设置顶点数据的格式
    glVertexAttribPointer(positionHandle, 3, GL_FLOAT, GL_FALSE, 0, (void*)0);
    glEnableVertexAttribArray(positionHandle);
}

void OpenGLRenderer::DrawRectangle() {
    // 使用传入的EGLContext上下文
    eglMakeCurrent(dpy, surface, surface, eglContext);

    //glClear(GL_COLOR_BUFFER_BIT);  // 清除颜色缓存

    // 设置红色
    glUniform4fv(colorHandle, 1, color);

    // 绘制矩形
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    // 交换缓冲区显示结果
    //eglSwapBuffers(dpy, surface);
}

void OpenGLRenderer::CleanupOpenGL() {
    glDeleteBuffers(1, &vbo);
    glDeleteProgram(program);
}

