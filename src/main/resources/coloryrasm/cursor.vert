#version 450
layout(push_constant) uniform Position { vec2 origin; vec2 scale; } pos;
layout(location = 0) out vec2 uv;
void main() {
    uv = vec2((gl_VertexIndex == 1 || gl_VertexIndex == 3) ? 1.0 : 0.0,
              gl_VertexIndex >= 2 ? 1.0 : 0.0);
    gl_Position = vec4(pos.origin + uv * pos.scale, 0.0, 1.0);
}
