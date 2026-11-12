package dev.anydisplay.client.video

/*
 * AMD FidelityFX Super Resolution 1.0 (FSR 1) shader math for OpenGL ES 2.0/3.0.
 *
 * Ported from the MIT-licensed reference implementation
 * https://github.com/GPUOpen-Effects/FidelityFX-FSR/blob/master/ffx-fsr/ffx_fsr1.h
 * (AMDFidelityFX FSR 1 v1.20210629) : FsrEasuF()/FsrEasuSetF()/FsrEasuTapF() and FsrRcasF(), and the constant
 * setup of FsrEasuCon()/FsrRcasCon(). The two passes are kept separate, exactly as in the reference:
 * EASU upscales with its 12-tap edge-adaptive kernel, then RCAS sharpens the EASU output with its 5-tap
 * robust contrast adaptive filter.
 *
 *   Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.
 *   Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 *   associated documentation files (the "Software"), to deal in the Software without restriction, including
 *   without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the
 *   following conditions:
 *   The above copyright notice and this permission notice shall be included in all copies or substantial
 *   portions of the Software.
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 *   LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 *   NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 *   WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 *   SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * Porting notes (no math was changed):
 * - gather4 of the reference is emulated with the twelve individual texel taps it decomposes into
 *   (see the comment inside EASU_FRAGMENT). They lie in one 4x4 texel neighborhood around floor(pp).
 * - The rcp/rsq approximations of ffx_a.h (APrxLoRcpF1, APrxMedRcpF1, APrxLoRsqF1) are bit tricks for speed on
 *   desktop GPUs. Here their exact counterparts (1.0/x, inversesqrt) are used, which only improves precision.
 * - The user sharpness is the RCAS constant from FsrRcasCon(): exp2(-stops), where 0 stops is maximum
 *   sharpening and larger values reduce it. The 0-100 preference is mapped to 2..0 stops by the renderer.
 * - FSR_RCAS_DENOISE is not enabled, so the noise term is omitted from the RCAS pass.
 * - All fragment shaders are GLSL ES 1.00 so they link in both ES 2.0 and ES 3.0 contexts.
 */
internal object FsrShaders {

    /** Full-screen triangle strip; vUv spans the current viewport (0,0 bottom-left to 1,1 top-right). */
    const val VERTEX = """
attribute vec4 aPos;
varying vec2 vUv;
void main() {
    gl_Position = vec4(aPos.xy, 0.0, 1.0);
    vUv = aPos.xy * 0.5 + 0.5;
}
"""

    /**
     * EASU: edge-adaptive spatial upscale from the decoded frame to the display size.
     *
     * uCon0 = FsrEasuCon() con0 (output pixel -> input pixel scale/offset) and uCon1.xy = con1.xy
     * (1 / input size). The reference derives four gather4 positions p0..p3 from con1..con3; unrolled,
     * those blocks cover the 4x4 texel neighborhood at (fp.x-1, fp.y-1) that the twelve taps below sample,
     * so con2/con3 are not needed.
     */
    const val EASU_FRAGMENT = """
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform samplerExternalOES uSrc;
uniform mat4 uTexMatrix;
uniform vec4 uCon0;
uniform vec4 uCon1;

#define SRC_TAP(off) texture2D(uSrc, (uTexMatrix * vec4((clamp(fp + (off), vec2(0.0), uCon1.zw) + 0.5) * uCon1.xy, 0.0, 1.0)).xy).rgb

float luma(vec3 c) {
    return c.b * 0.5 + (c.r * 0.5 + c.g);
}

// FsrEasuTapF(): one tap of the anisotropic negative-lobe kernel.
void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len2, float lob, float clp, vec3 c) {
    vec2 v;
    v.x = off.x * dir.x + off.y * dir.y;
    v.y = off.x * (-dir.y) + off.y * dir.x;
    v *= len2;
    float d2 = v.x * v.x + v.y * v.y;
    d2 = min(d2, clp);
    // Approximation of lanczos2 without sin() or sqrt().
    float wB = (2.0 / 5.0) * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = (25.0 / 16.0) * wB - (25.0 / 16.0 - 1.0);
    float w = wB * wA;
    aC += c * w;
    aW += w;
}

// FsrEasuSetF(): accumulate the '+' gradient direction and edge length for one bilinear corner.
void easuSet(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {
    float dc = lD - lC;
    float cb = lC - lB;
    float lenX = max(abs(dc), abs(cb));
    lenX = 1.0 / max(lenX, 1e-8);
    float dirX = lD - lB;
    dir.x += dirX * w;
    lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
    lenX *= lenX;
    len += lenX * w;
    float ec = lE - lC;
    float ca = lC - lA;
    float lenY = max(abs(ec), abs(ca));
    lenY = 1.0 / max(lenY, 1e-8);
    float dirY = lE - lA;
    dir.y += dirY * w;
    lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
    lenY *= lenY;
    len += lenY * w;
}

void main() {
    vec2 pp = floor(gl_FragCoord.xy) * uCon0.xy + uCon0.zw;
    vec2 fp = floor(pp);
    pp -= fp;

    //    b c
    //  e f g h
    //  i j k l
    //    n o
    vec3 b = SRC_TAP(vec2(0.0, -1.0));
    vec3 c = SRC_TAP(vec2(1.0, -1.0));
    vec3 e = SRC_TAP(vec2(-1.0, 0.0));
    vec3 f = SRC_TAP(vec2(0.0, 0.0));
    vec3 g = SRC_TAP(vec2(1.0, 0.0));
    vec3 h = SRC_TAP(vec2(2.0, 0.0));
    vec3 i = SRC_TAP(vec2(-1.0, 1.0));
    vec3 j = SRC_TAP(vec2(0.0, 1.0));
    vec3 k = SRC_TAP(vec2(1.0, 1.0));
    vec3 l = SRC_TAP(vec2(2.0, 1.0));
    vec3 n = SRC_TAP(vec2(0.0, 2.0));
    vec3 o = SRC_TAP(vec2(1.0, 2.0));

    vec2 dir = vec2(0.0);
    float len = 0.0;
    easuSet(dir, len, (1.0 - pp.x) * (1.0 - pp.y), luma(b), luma(e), luma(f), luma(g), luma(j));
    easuSet(dir, len, pp.x * (1.0 - pp.y), luma(c), luma(f), luma(g), luma(h), luma(k));
    easuSet(dir, len, (1.0 - pp.x) * pp.y, luma(f), luma(i), luma(j), luma(k), luma(n));
    easuSet(dir, len, pp.x * pp.y, luma(g), luma(j), luma(k), luma(l), luma(o));

    vec2 dir2 = dir * dir;
    float dirR = dir2.x + dir2.y;
    bool zro = dirR < (1.0 / 32768.0);
    dirR = inversesqrt(max(dirR, 1e-8));
    if (zro) {
        dirR = 1.0;
        dir.x = 1.0;
    }
    dir *= dirR;
    // Stretch the kernel from {1.0 vert|horz, to sqrt(2.0) on diagonal}.
    len *= 0.5;
    len *= len;
    float stretch = (dir.x * dir.x + dir.y * dir.y) / max(abs(dir.x), abs(dir.y));
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
    float clp = 1.0 / max(lob, 1e-8);

    vec3 min4 = min(min(f, g), min(j, k));
    vec3 max4 = max(max(f, g), max(j, k));
    vec3 aC = vec3(0.0);
    float aW = 0.0;
    easuTap(aC, aW, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);
    easuTap(aC, aW, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);
    easuTap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);
    easuTap(aC, aW, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);
    easuTap(aC, aW, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);
    easuTap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);
    easuTap(aC, aW, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);
    easuTap(aC, aW, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);
    easuTap(aC, aW, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);
    easuTap(aC, aW, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);
    easuTap(aC, aW, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, o);
    easuTap(aC, aW, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);

    gl_FragColor = vec4(clamp(aC / max(aW, 1e-8), min4, max4), 1.0);
}
"""

    /**
     * RCAS: robust contrast adaptive sharpening of the EASU output, one texel per fragment.
     * uSharp = FsrRcasCon() con.x = exp2(-stops), 1.0 for maximum sharpening.
     */
    const val RCAS_FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uSrc;
uniform vec2 uInvSize;
uniform float uSharp;
varying vec2 vUv;

#define FSR_RCAS_LIMIT (0.25 - 1.0 / 16.0)

void main() {
    //    b
    //  d e f
    //    h
    vec3 b = texture2D(uSrc, vUv + vec2(0.0, -uInvSize.y)).rgb;
    vec3 d = texture2D(uSrc, vUv + vec2(-uInvSize.x, 0.0)).rgb;
    vec3 e = texture2D(uSrc, vUv).rgb;
    vec3 f = texture2D(uSrc, vUv + vec2(uInvSize.x, 0.0)).rgb;
    vec3 h = texture2D(uSrc, vUv + vec2(0.0, uInvSize.y)).rgb;

    // Min and max of the ring.
    vec3 mn4 = min(min(b, d), min(f, h));
    vec3 mx4 = max(max(b, d), max(f, h));
    // Solve for the largest lobe that does not clip the {0 to 1} range.
    // Guard the divisions: Mali propagates NaN from 0/0 instead of clamping, which would black out
    // flat dark areas. Same epsilon guards as the reference implementation.
    vec3 hitMin = min(mn4, e) / max(4.0 * mx4, vec3(1.0e-6));
    vec3 hitMax = (1.0 - max(mx4, e)) / min(4.0 * mn4 - 4.0, vec3(-1.0e-6));
    vec3 lobe3 = max(-hitMin, hitMax);
    float lobe = max(-FSR_RCAS_LIMIT, min(max(lobe3.r, max(lobe3.g, lobe3.b)), 0.0)) * uSharp;

    float rcpL = 1.0 / (4.0 * lobe + 1.0);
    gl_FragColor = vec4(clamp((lobe * (b + d + f + h) + e) * rcpL, 0.0, 1.0), 1.0);
}
"""

    /** Nearest neighbor: the decoded frame sampled with GL_NEAREST, one source texel per destination pixel. */
    const val NEAREST_FRAGMENT = """
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform samplerExternalOES uSrc;
uniform mat4 uTexMatrix;
uniform vec2 uVideoSize;
varying vec2 vUv;

void main() {
    // Snap to the source texel center before the SurfaceTexture matrix, like the known-working reference.
    vec2 uv = (floor(vUv * uVideoSize) + 0.5) / uVideoSize;
    gl_FragColor = vec4(texture2D(uSrc, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy).rgb, 1.0);
}
"""
}
