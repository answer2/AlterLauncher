//
// Created by Aanswer_Dev on 2025/2/2.
//

#ifndef ALTERLAUNCHER_OPENGLRENDERER_H
#define ALTERLAUNCHER_OPENGLRENDERER_H

#include <GLES2/gl2.h>
#include <EGL/egl.h>

class OpenGLRenderer {
public:
    // 构造函数，传入EGLDisplay, EGLSurface 和 EGLContext
    OpenGLRenderer(EGLDisplay display, EGLSurface surface, EGLContext context);

    // 析构函数，清理OpenGL资源
    ~OpenGLRenderer();

    // 绘制红色矩形
    void DrawRectangle();

private:
    // OpenGL ES 环境所需的成员变量
    EGLDisplay dpy;
    EGLSurface surface;
    EGLContext eglContext;

    GLuint program;               // 着色器程序ID
    GLuint vbo;                   // 顶点缓冲对象（VBO）
    GLint positionHandle, colorHandle;  // 顶点位置和颜色句柄

    // 顶点着色器和片段着色器代码
    const char* vertexShaderCode;
    const char* fragmentShaderCode;

    // 矩形的顶点数据
    GLfloat vertices[12];

    // 红色的RGBA颜色值
    GLfloat color[4];

    // 初始化OpenGL环境（编译着色器，设置顶点数据等）
    void InitOpenGL();

    // 清理OpenGL资源（删除VBO，着色器等）
    void CleanupOpenGL();
};


#endif //ALTERLAUNCHER_OPENGLRENDERER_H
