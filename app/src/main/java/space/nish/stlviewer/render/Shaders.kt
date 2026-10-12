package space.nish.stlviewer.render

import android.opengl.GLES30
import android.util.Log

internal object Shaders {

    /** Mesh vertex shader. Barycentrics come from gl_VertexID so wireframe needs no extra attribute. */
    const val MESH_VERTEX = """#version 300 es
layout(location = 0) in vec3 aPosition;
uniform mat4 uView;
uniform mat4 uMvp;
out vec3 vViewPos;
out vec3 vBary;
void main() {
    vec4 p = vec4(aPosition, 1.0);
    vViewPos = (uView * p).xyz;
    int corner = gl_VertexID % 3;
    vBary = vec3(corner == 0 ? 1.0 : 0.0, corner == 1 ? 1.0 : 0.0, corner == 2 ? 1.0 : 0.0);
    gl_Position = uMvp * p;
}
"""

    /**
     * Flat shading from screen-space derivatives + Blinn-Phong with a headlight and a fill light.
     * uMode: 0 = shaded, 1 = shaded + edges, 2 = wireframe only.
     */
    const val MESH_FRAGMENT = """#version 300 es
precision highp float;
in vec3 vViewPos;
in vec3 vBary;
uniform vec3 uColor;
uniform vec3 uEdgeColor;
uniform int uMode;
out vec4 fragColor;

float edgeFactor() {
    vec3 d = fwidth(vBary);
    vec3 a = smoothstep(vec3(0.0), d * 1.25, vBary);
    return 1.0 - min(min(a.x, a.y), a.z);
}

void main() {
    vec3 n = normalize(cross(dFdx(vViewPos), dFdy(vViewPos)));
    vec3 v = normalize(-vViewPos);
    if (dot(n, v) < 0.0) n = -n;

    vec3 base = uColor;
    if (!gl_FrontFacing) base *= vec3(0.75, 0.55, 0.55); // hint at inside / inverted faces

    // Key light slightly above-left of the camera, fill light from below-right (view space).
    vec3 key = normalize(vec3(-0.35, 0.55, 1.0));
    vec3 fill = normalize(vec3(0.6, -0.4, 0.5));
    float diffuse = max(dot(n, key), 0.0) * 0.75 + max(dot(n, fill), 0.0) * 0.25;
    float spec = pow(max(dot(n, normalize(key + v)), 0.0), 48.0) * 0.25;
    float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * 0.12;
    vec3 color = base * (0.22 + diffuse) + vec3(spec + rim);

    if (uMode == 0) {
        fragColor = vec4(color, 1.0);
        return;
    }
    float e = edgeFactor();
    if (uMode == 2) {
        if (e < 0.05) discard;
        fragColor = vec4(uColor, e);
        return;
    }
    fragColor = vec4(mix(color, uEdgeColor, e * 0.85), 1.0);
}
"""

    const val LINE_VERTEX = """#version 300 es
layout(location = 0) in vec3 aPosition;
uniform mat4 uMvp;
void main() {
    gl_Position = uMvp * vec4(aPosition, 1.0);
}
"""

    const val LINE_FRAGMENT = """#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 fragColor;
void main() {
    fragColor = uColor;
}
"""
}

internal object GlUtil {
    private const val TAG = "GlUtil"

    fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw IllegalStateException("Program link failed: $log")
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            Log.e(TAG, "Shader compile failed: $log\n$source")
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("Shader compile failed: $log")
        }
        return shader
    }
}
