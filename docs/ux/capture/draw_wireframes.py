"""Draws docs/ux/wireframes.svg: desktop dialog and Android capture screen side by side, one row per state.

Usage (repository root): python docs/ux/capture/draw_wireframes.py docs/ux/wireframes.svg
Texts are copied from docs/ux/message-catalogue.csv; states, icons and severities from LivenessUiStateMapper.
Change the STATES table and draw the sheet again when a text or state changes.
"""
import sys
from xml.sax.saxutils import escape

OUT = sys.argv[1]

W = 1440
ROW_H = 356
HEAD_H = 200
FONT = "Segoe UI, Roboto, Helvetica, Arial, sans-serif"

# Desktop banner colours from docs/ux/desktop-liveness.md section 4 (contrast checked there).
DESK = {
    "info": ("#E8F1FA", "#0B2E4F", "#005BAA"),
    "warning": ("#FFF4DB", "#5B3B00", "#8A5A00"),
    "error": ("#FCE8E6", "#7A1712", "#B3261E"),
    "success": ("#E6F4EA", "#14532D", "#1E7B3C"),
}
DESK_OVAL = {"info": "#FFFFFF", "warning": "#F2B01E", "error": "#F26B5B", "success": "#3DBE6B", "off": "#5F6B76"}
AND_ACC = {"info": "#FFFFFF", "warning": "#FFB300", "error": "#EF5350", "success": "#66BB6A", "off": "#5F6B76"}
AND_ICON = {"info": "#4FC3F7", "warning": "#FFB300", "error": "#FF8A80", "success": "#81C784"}

out = []


def t(x, y, s, size=11, fill="#1F2933", weight="normal", anchor="start", family=FONT, italic=False):
    st = ' font-style="italic"' if italic else ""
    out.append(
        f'<text x="{x:.1f}" y="{y:.1f}" font-family="{family}" font-size="{size}" fill="{fill}" '
        f'font-weight="{weight}" text-anchor="{anchor}"{st}>{escape(s)}</text>'
    )


def wrap(s, width, size, bold=False):
    per = width / (size * (0.53 if bold else 0.49))
    words, lines, cur = s.split(), [], ""
    for w in words:
        cand = (cur + " " + w).strip()
        if len(cand) > per and cur:
            lines.append(cur)
            cur = w
        else:
            cur = cand
    if cur:
        lines.append(cur)
    return lines


def tw(x, y, s, width, size=11, fill="#1F2933", weight="normal", lh=None, italic=False):
    lh = lh or size * 1.3
    lines = wrap(s, width, size, weight == "bold")
    for i, line in enumerate(lines):
        t(x, y + i * lh, line, size, fill, weight, italic=italic)
    return y + len(lines) * lh


def rect(x, y, w, h, fill="none", stroke="none", sw=1, rx=0, dash=None, extra=""):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    out.append(
        f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h:.1f}" rx="{rx}" fill="{fill}" '
        f'stroke="{stroke}" stroke-width="{sw}"{d}{extra}/>'
    )


def icon(cx, cy, kind, colour, disc):
    """disc: fill a circle with colour and draw the glyph in white (desktop); else glyph in colour (Android)."""
    g = "#FFFFFF" if disc else colour
    if disc:
        out.append(f'<circle cx="{cx}" cy="{cy}" r="12" fill="{colour}"/>')
    s = f'fill="none" stroke="{g}" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"'
    if kind == "face":
        out.append(f'<ellipse cx="{cx}" cy="{cy}" rx="4.5" ry="6.5" {s}/>')
    elif kind == "progress":
        out.append(f'<path d="M {cx+6} {cy} A 6 6 0 1 1 {cx} {cy-6}" {s}/>')
    elif kind == "eye":
        out.append(f'<path d="M {cx-7} {cy} Q {cx} {cy-7} {cx+7} {cy} Q {cx} {cy+7} {cx-7} {cy} Z" {s}/>')
        out.append(f'<circle cx="{cx}" cy="{cy}" r="2" fill="{g}"/>')
    elif kind == "check":
        out.append(f'<polyline points="{cx-5},{cy} {cx-1.5},{cy+4} {cx+5.5},{cy-4}" {s}/>')
    elif kind == "hold":
        out.append(f'<circle cx="{cx}" cy="{cy}" r="6" {s}/>')
        out.append(f'<circle cx="{cx}" cy="{cy}" r="2" fill="{g}"/>')
    elif kind == "warning":
        out.append(f'<path d="M {cx} {cy-7} L {cx+7} {cy+5.5} L {cx-7} {cy+5.5} Z" {s}/>')
        out.append(f'<line x1="{cx}" y1="{cy-2.5}" x2="{cx}" y2="{cy+1.2}" {s}/>')
        out.append(f'<circle cx="{cx}" cy="{cy+3.4}" r="0.9" fill="{g}"/>')
    elif kind == "error":
        out.append(f'<circle cx="{cx}" cy="{cy}" r="6.5" {s}/>')
        out.append(f'<line x1="{cx}" y1="{cy-3.5}" x2="{cx}" y2="{cy+0.8}" {s}/>')
        out.append(f'<circle cx="{cx}" cy="{cy+3.2}" r="0.9" fill="{g}"/>')
    elif kind == "lock":
        out.append(f'<rect x="{cx-5}" y="{cy-1}" width="10" height="8" rx="1.5" fill="{g}"/>')
        out.append(f'<path d="M {cx-3} {cy-1} V {cy-4} A 3 3 0 0 1 {cx+3} {cy-4} V {cy-1}" {s}/>')
    elif kind == "camoff":
        out.append(f'<rect x="{cx-7}" y="{cy-4}" width="10" height="8" rx="1.5" {s}/>')
        out.append(f'<path d="M {cx+3} {cy-1.5} L {cx+7} {cy-3.5} V {cy+3.5} L {cx+3} {cy+1.5}" {s}/>')
        out.append(f'<line x1="{cx-7}" y1="{cy-7}" x2="{cx+7}" y2="{cy+7}" {s}/>')


def faces(cx, cy, n, colour):
    """Grey head-and-shoulder shapes standing in for the camera image (no real face in the drawings)."""
    offs = [0] if n == 1 else [-26, 26]
    for o in offs:
        out.append(f'<ellipse cx="{cx+o}" cy="{cy-8}" rx="15" ry="19" fill="{colour}"/>')
        out.append(f'<path d="M {cx+o-28} {cy+38} Q {cx+o} {cy+4} {cx+o+28} {cy+38} Z" fill="{colour}"/>')


def desktop(x, y, s):
    w, h = 250, 330
    rect(x, y, w, h, "#FFFFFF", "#9AA5B1", 1, 6)
    t(x + 12, y + 20, s.get("title", "Face verification"), 12, "#00467F", "bold")
    # preview
    px, py, pw, ph = x + 10, y + 30, 230, 130
    rect(px, py, pw, ph, "#1D2025", rx=4)
    if s.get("faces"):
        faces(px + pw / 2, py + ph / 2, s["faces"], "#4A525C")
    sev = s["sev"]
    oval = s.get("oval", sev)
    if oval != "none":
        dash = "7 5" if s.get("dashed", True) else None
        d = f' stroke-dasharray="{dash}"' if dash else ""
        out.append(
            f'<ellipse cx="{px+pw/2}" cy="{py+ph/2}" rx="42" ry="54" fill="none" stroke="{DESK_OVAL[oval]}" '
            f'stroke-width="2.4"{d}/>'
        )
    if s.get("offline"):
        rect(px + 6, py + ph - 22, 196, 16, "#5B4200", rx=8)
        t(px + 14, py + ph - 10.5, "Offline – verification runs on this device", 8.5, "#FFFFFF", "bold")
    bg, fg, bar = DESK[sev]
    # banner
    by, bh = y + 166, 108
    rect(x + 10, by, 230, bh, bg, rx=4)
    rect(x + 10, by, 3, bh, bar)
    icon(x + 30, by + 22, s["icon"], bar, True)
    yy = tw(x + 48, by + 17, s["prompt"], 186, 12, fg, "bold", 14)
    for gl in s.get("guidance", []):
        yy = tw(x + 48, yy + 2, gl, 186, 10, "#1F2933", lh=12)
    if s.get("busy"):
        t(x + 48, yy + 3, "(busy indicator)", 10, fg, italic=True)
    # progress and counters
    ry = y + 284
    rect(x + 10, ry, 110, 5, "#DDE3EA", rx=2.5)
    if s.get("progress"):
        rect(x + 10, ry, 110 * s["progress"], 5, "#005BAA", rx=2.5)
    counters = []
    if s.get("step"):
        counters.append(s["step"])
    if s.get("attempt"):
        counters.append(s["attempt"])
    t(x + 240, ry + 6, "   ".join(counters), 9.5, "#1F2933", anchor="end")
    # buttons, right-aligned
    bx = x + 240
    for label, kind in reversed(s.get("buttons", [])):
        bw = 12 + len(label) * 6.2
        bx -= bw
        if kind in ("primary", "focus"):
            rect(bx, y + 298, bw, 22, "#005BAA", rx=4)
            t(bx + bw / 2, y + 313, label, 10, "#FFFFFF", "bold", "middle")
        else:
            rect(bx, y + 298, bw, 22, "#FFFFFF", "#0B2E4F", 1.4, 4)
            t(bx + bw / 2, y + 313, label, 10, "#005BAA", anchor="middle")
        if kind == "focus":
            rect(bx - 3, y + 295, bw + 6, 28, "none", "#F2B01E", 2.5, 6)
        bx -= 6
    if s.get("dialog"):
        dialog(x + 14, y + 52, 222, s["dialog"], light=True)


def dialog(x, y, w, d, light):
    h = 112
    fill, fg, sub, btn = ("#FFFFFF", "#0B2E4F", "#1F2933", "#005BAA") if light else ("#3A3F44", "#FFFFFF", "#E3E7EA", "#4FC3F7")
    rect(x, y, w, h, fill, "#5F6B76", 1, 4, extra=' filter="url(#shadow)"')
    t(x + 10, y + 17, d["title"], 11, fg, "bold")
    yy = tw(x + 10, y + 33, d["text"], w - 20, 9.5, sub, lh=12)
    if d.get("field"):
        out.append(f'<line x1="{x+10}" y1="{yy+10}" x2="{x+w-10}" y2="{yy+10}" stroke="{btn}" stroke-width="1.2"/>')
        t(x + 12, yy + 7, d["field"], 9.5, sub, italic=True)
    bx = x + w - 10
    for label in reversed(d["buttons"]):
        bw = len(label) * 6 + 10
        bx -= bw
        t(bx + bw / 2, y + h - 10, label, 9.5, btn, "bold", "middle")
        bx -= 4


def android(x, y, s):
    w, h = 480, 300
    rect(x, y, w, h, "#000000", "#9AA5B1", 1, 6)
    t(x + w / 2, y + 22, s.get("title", "Face verification"), 11.5, "#FFFFFF", "bold", "middle")
    badge = s.get("badge")
    if badge:
        bw = len(badge) * 5.2 + 18
        rect(x + w / 2 - bw / 2, y + 30, bw, 15, "#2E3A44" if "Offline" not in badge else "#5B4200", rx=7.5)
        t(x + w / 2, y + 41, badge, 8.5, "#FFFFFF", anchor="middle")
    cx, cy = x + w / 2, y + 108
    if s.get("faces"):
        faces(cx, cy, s["faces"], "#2F353C")
    sev = s["sev"]
    oval = s.get("oval", sev)
    if oval != "none":
        out.append(f'<ellipse cx="{cx}" cy="{cy}" rx="50" ry="62" fill="none" stroke="{AND_ACC[oval]}" stroke-width="2.2"/>')
    if s.get("arc"):
        # progress arc on the right half of the oval
        out.append(
            f'<path d="M {cx} {cy-62} A 50 62 0 0 1 {cx} {cy+62}" fill="none" stroke="#4FC3F7" stroke-width="4" '
            f'stroke-linecap="round"/>'
        )
    # bottom panel
    py = y + 186
    out.append(
        f'<path d="M {x} {py+10} Q {x} {py} {x+10} {py} H {x+w-10} Q {x+w} {py} {x+w} {py+10} V {y+h-6} '
        f'Q {x+w} {y+h} {x+w-6} {y+h} H {x+6} Q {x} {y+h} {x} {y+h-6} Z" fill="#12181F"/>'
    )
    icon(x + 20, py + 18, s["icon"], AND_ICON[sev], False)
    yy = tw(x + 36, py + 23, s["prompt"], 430, 13, "#FFFFFF", "bold", 15)
    for gl in s.get("guidance", []):
        yy = tw(x + 36, yy + 2, gl, 430, 10.5, "#CFD8DC", lh=12.5)
    if s.get("busy"):
        t(x + 36, yy + 3, "(busy indicator)", 10.5, "#CFD8DC", italic=True)
        yy += 10
    if s.get("step"):
        t(x + 14, max(yy + 6, py + 56), s["step"], 10, "#CFD8DC")
    ly = py + 64
    out.append(f'<line x1="{x+14}" y1="{ly}" x2="{x+w-14}" y2="{ly}" stroke="#3B4650" stroke-width="2"/>')
    if s.get("progress"):
        out.append(
            f'<line x1="{x+14}" y1="{ly}" x2="{x+14+(w-28)*s["progress"]}" y2="{ly}" stroke="#4FC3F7" stroke-width="2"/>'
        )
    dots = s.get("dots")
    if dots:
        for i, c in enumerate(dots):
            col = {"f": "#FF8A80", "c": "#FFFFFF", "n": "#6B7680"}[c]
            out.append(f'<circle cx="{x+18+i*11}" cy="{ly+11}" r="3.2" fill="{col}"/>')
    bx = x + w - 12
    for label, kind in reversed(s.get("buttons", [])):
        bw = len(label) * 6.8 + 16
        bx -= bw
        primary = kind in ("primary", "focus")
        rect(bx, y + h - 30, bw, 20, "#4FC3F7" if primary else "#12181F", "#4FC3F7" if primary else "#3B4650", 1, 3)
        t(bx + bw / 2, y + h - 16.5, label, 9.5, "#0B1A24" if primary else "#FFFFFF", "bold", "middle")
        if kind == "focus":
            rect(bx - 3, y + h - 33, bw + 6, 26, "none", "#FFB300", 2, 5)
        bx -= 6
    if s.get("dialog"):
        rect(x, y, w, h, "#000000", extra=' fill-opacity="0.45"')
        dialog(x + 90, y + 70, 300, s["dialog"], light=False)


def legend(x, y):
    items = [("info", "Information and prompts"), ("warning", "Positioning problem"), ("error", "Failure, device error"), ("success", "Action detected, success")]
    for i, (k, label) in enumerate(items):
        bg, fg, bar = DESK[k]
        xx = x + i * 340
        rect(xx, y, 22, 16, bg, bar, 1.5, 3)
        t(xx + 30, y + 12, f"{label} (desktop banner {bg})", 11.5, "#1F2933")


O_ = ("Cancel", "outline")
R_ = ("Retry", "primary")
RF = ("Retry", "focus")
CL = ("Close", "outline")
AC = ("CANCEL", "outline")
AR = ("RETRY", "primary")

GUIDE_LOOK = "Look directly at the camera."
RETRY1 = "Attempt 1 of 3. Select Retry to try again."
RETRY2 = "Attempt 2 of 3. Select Retry to try again."
MAX_RES = "Face capture could not be completed after 3 attempts. Please ask your supervisor to follow the centre's recovery process."

STATES = [
    dict(
        name="1. Live preview with positioning guide",
        src="PS04 10.1 (live preview, positioning guidance)",
        key="liveness.guide.look_camera; problems: liveness.guide.face_visible, .in_area, .lighting",
        d=dict(sev="info", oval="info", faces=1, icon="face", prompt=GUIDE_LOOK, attempt="Attempt 1 of 3", progress=0.05, buttons=[O_]),
        a=dict(sev="info", oval="info", faces=1, icon="face", prompt=GUIDE_LOOK, dots="cnn", progress=0.05, buttons=[AC]),
        notes=[
            "Dashed oval = where the face must be. A positioning problem turns the banner amber with a warning icon and one PS04 guidance line.",
            "No attempt is used while positioning.",
            "Pictures: desktop d-r1-positioning.png; Android none yet.",
        ],
    ),
    dict(
        name="2. Checking face liveness…",
        src="PS04 10.2 (passive check, no action)",
        key="liveness.status.checking",
        d=dict(sev="info", oval="info", faces=1, icon="progress", prompt="Checking face liveness…", busy=True, attempt="Attempt 1 of 3", progress=0.3, buttons=[O_]),
        a=dict(sev="info", oval="info", faces=1, icon="progress", prompt="Checking face liveness…", busy=True, dots="cnn", progress=0.3, buttons=[AC]),
        notes=[
            "The resident does nothing; the client moves on by itself when the passive score is enough.",
            "No score or model name is shown (only in diagnostic mode, for a supervisor).",
            "Pictures: none yet on either client.",
        ],
    ),
    dict(
        name="3. Challenge prompt",
        src="PS04 10.3 (system-chosen action)",
        key="liveness.challenge.blink, .smile, .turn_left, ... ; liveness.ui.step",
        d=dict(sev="info", oval="info", faces=1, icon="eye", prompt="Please blink", step="Step 1 of 2", attempt="Attempt 1 of 3", progress=0.25, buttons=[O_]),
        a=dict(sev="info", oval="info", faces=1, arc=True, icon="eye", prompt="Please blink", step="Step 1 of 2", dots="cnn", progress=0.25, buttons=[AC]),
        notes=[
            "The client picks the action at random; the resident never chooses it. One icon per action (eye, smile, arrow).",
            "After half the time for the action, the banner turns amber and adds \"Follow the prompted action.\"",
            "Pictures: desktop e2e-03 (\"Please smile\"); Android e2e-06 (\"Please smile\").",
        ],
    ),
    dict(
        name="4. Action detected",
        src="PS04 10.4 (real-time feedback)",
        key="liveness.feedback.detected",
        d=dict(sev="success", oval="success", faces=1, icon="check", prompt="Action detected", step="Step 1 of 2", attempt="Attempt 1 of 3", progress=0.5, buttons=[O_]),
        a=dict(sev="success", oval="success", faces=1, arc=True, icon="check", prompt="Action detected", step="Step 1 of 2", dots="cnn", progress=0.5, buttons=[AC]),
        notes=[
            "Shown briefly after each answered action, then the next step (\"Step 2 of 2\") or Hold still.",
            "Pictures: none yet; the texts and step counter are covered by unit tests.",
        ],
    ),
    dict(
        name="5. Hold still",
        src="PS04 10.4 (real-time feedback)",
        key="liveness.feedback.hold_still",
        d=dict(sev="info", oval="info", dashed=False, faces=1, icon="hold", prompt="Hold still", attempt="Attempt 1 of 3", progress=0.9, buttons=[O_]),
        a=dict(sev="info", oval="info", faces=1, icon="hold", prompt="Hold still", dots="cnn", progress=0.9, buttons=[AC]),
        notes=[
            "The live check has passed; the stream stays open while the device takes the signed capture.",
            "Pictures: none yet (desktop state seen in the 2026-09-30 run log).",
        ],
    ),
    dict(
        name="6. Success",
        src="PS04 10.4",
        key="liveness.feedback.success",
        d=dict(sev="success", oval="success", dashed=False, faces=1, icon="check", prompt="Good, face captured successfully", attempt="Attempt 1 of 3", progress=1.0, buttons=[]),
        a=dict(sev="success", oval="success", faces=1, icon="check", prompt="Good, face captured successfully", dots="cnn", progress=1.0, buttons=[]),
        notes=[
            "Desktop: the window closes after 0.9 s and the capture screen shows the face. Android: the screen closes and the ARC biometrics page shows the same text in a green banner.",
            "Pictures: Android a-r1-resident-face-passed.png (ARC banner); desktop none.",
        ],
    ),
    dict(
        name="7. Liveness failure with guidance",
        src="PS04 10.5",
        key="liveness.fail.liveness + one liveness.guide.* line + liveness.retry.available",
        d=dict(sev="error", oval="error", faces=1, icon="error", prompt="We could not verify face liveness. Please try again.", guidance=["Follow the prompted action.", RETRY1], attempt="Attempt 1 of 3", progress=0.33, buttons=[R_, O_]),
        a=dict(sev="error", oval="error", faces=1, icon="error", prompt="We could not verify face liveness. Please try again.", guidance=["Follow the prompted action.", RETRY1], dots="fcn", buttons=[AC, AR]),
        notes=[
            "Used when liveness was not shown (timeout, wrong action, low score) but nothing points to an attack.",
            "The guidance line names what to change: look at the camera, face visible, lighting, capture area, follow the action.",
            "Pictures: desktop e2e-05-print-refused.png; Android e2e-03-sign-in-replay-refused.png.",
        ],
    ),
    dict(
        name="8. Generic PAD failure",
        src="PS04 10.6 (no security detail)",
        key="liveness.fail.pad + liveness.retry.available",
        d=dict(sev="error", oval="error", faces=1, icon="error", prompt="Face verification could not be completed. Please try again.", guidance=[RETRY1], attempt="Attempt 1 of 3", progress=0.33, buttons=[R_, O_]),
        a=dict(sev="error", oval="error", faces=1, icon="error", prompt="Face verification could not be completed. Please try again.", guidance=[RETRY1], dots="fcn", buttons=[AC, AR]),
        notes=[
            "Same words for every suspected attack (photo, screen, replay, injected stream, face swap); the reason goes only to the audit log and diagnostic mode.",
            "Pictures: desktop d-r7-pad-fail-retry.png, e2e-06; Android e2e-05-resident-replay-refused.png.",
        ],
    ),
    dict(
        name="9. Retry available",
        src="PS04 10.7 (retry clearly indicated)",
        key="liveness.retry.available, liveness.ui.attempts, liveness.ui.retry",
        d=dict(sev="error", oval="error", faces=1, icon="error", prompt="Face verification could not be completed. Please try again.", guidance=[RETRY2], attempt="Attempt 2 of 3", progress=0.66, buttons=[RF, O_]),
        a=dict(sev="error", oval="error", faces=1, icon="error", prompt="Face verification could not be completed. Please try again.", guidance=[RETRY2], dots="ffc", buttons=[AC, AR]),
        notes=[
            "Retry appears only while attempts are left. Desktop: Retry takes keyboard focus (amber ring here) and Enter selects it.",
            "Android: one dot per attempt (red = failed, white = current, grey = left).",
            "Pictures: desktop d-r7-pad-fail-retry.png; Android e2e-05.",
        ],
    ),
    dict(
        name="10. Maximum attempts, recovery step",
        src="PS04 10.7 (recovery process)",
        key="liveness.retry.max_resident; sign-in: liveness.retry.max_auth",
        d=dict(sev="error", oval="error", faces=1, icon="lock", prompt=MAX_RES, attempt="Attempt 3 of 3", progress=1.0, buttons=[CL]),
        a=dict(sev="error", oval="error", faces=1, icon="lock", prompt=MAX_RES, dots="fff", buttons=[AC]),
        notes=[
            "No Retry. Sign-in and supervisor checks say instead: \"... Maximum attempts reached. Use another sign-in method or contact your supervisor.\"",
            "Pictures: desktop e2e-07-max-attempts-guidance.png; Android e2e-08-max-attempts.png, a-l2 (sign-in).",
        ],
    ),
    dict(
        name="11. Device error",
        src="team (PS04 10.7 recovery, task 5)",
        key="liveness.error.device_unavailable, .device_disconnected; Android: .camera_unavailable, .camera_permission",
        d=dict(sev="error", oval="error", icon="camoff", prompt="The face camera is not available. Check that it is connected and select Retry.", guidance=[RETRY1], attempt="Attempt 1 of 3", buttons=[R_, O_]),
        a=dict(sev="error", oval="error", icon="camoff", prompt="The face camera is not available. Check that it is connected and select Retry.", guidance=[RETRY1], dots="cnn", buttons=[AC, AR]),
        notes=[
            "A device error never uses an attempt: the counter stays at 1 of 3. On Android, a camera that cannot be opened or camera access turned off have their own texts; the second adds \"Open settings\".",
            "Pictures: desktop d-r10-device-error.png, e2e-02; Android a-r2-camera-unavailable.png, e2e-04.",
        ],
    ),
    dict(
        name="12. Multiple faces",
        src="team (positioning guidance)",
        key="liveness.error.multiple_faces",
        d=dict(sev="warning", oval="warning", faces=2, icon="warning", prompt="More than one face detected. Only the person being captured should be in view.", attempt="Attempt 1 of 3", progress=0.05, buttons=[O_]),
        a=dict(sev="warning", oval="warning", faces=2, icon="warning", prompt="More than one face detected. Only the person being captured should be in view.", dots="cnn", progress=0.05, buttons=[AC]),
        notes=[
            "A positioning problem: the check waits until only one face is in view; no attempt is used.",
            "Pictures: none yet on either client (two-face pack runs only in the scenario runner).",
        ],
    ),
    dict(
        name="13. Offline indicator",
        src="team (offline operation)",
        key="liveness.offline.badge",
        d=dict(sev="info", oval="info", faces=1, offline=True, icon="progress", prompt="Checking face liveness…", busy=True, attempt="Attempt 1 of 3", progress=0.3, buttons=[O_]),
        a=dict(sev="info", oval="info", faces=1, badge="Offline – verification runs on this device", icon="progress", prompt="Checking face liveness…", busy=True, dots="cnn", progress=0.3, buttons=[AC]),
        notes=[
            "Shown in any state when there is no network; the check itself never needs the network.",
            "Pictures: desktop d-l1-login-french-offline.png (French); Android none (screen capture failed in airplane mode).",
        ],
    ),
    dict(
        name="14. Supervisor override",
        src="team (resident capture after the last attempt)",
        key="liveness.ui.override, liveness.override.confirm",
        d=dict(
            sev="error", oval="error", faces=1, icon="lock", prompt=MAX_RES, attempt="Attempt 3 of 3", progress=1.0,
            buttons=[CL, ("Supervisor override", "outline")],
            dialog=dict(title="Supervisor verification", text="Supervisor override: this face will be flagged for review in the packet. Continue?", buttons=["OK", "Cancel"]),
        ),
        a=dict(
            sev="error", oval="error", faces=1, icon="lock", prompt=MAX_RES, dots="fff",
            buttons=[AC, ("SUPERVISOR OVERRIDE", "outline")],
            dialog=dict(title="Supervisor verification", text="Supervisor override: this face will be flagged for review in the packet. Continue?", field="Supervisor user ID", buttons=["CANCEL", "SUPERVISOR OVERRIDE"]),
        ),
        notes=[
            "Only for resident capture with on_max_failure = SUPERVISOR_OVERRIDE, and never after a suspected attack. The packet marks the face LIVENESS_DECISION=OVERRIDE.",
            "Who confirms and how: docs/design/desktop-liveness-ui.md and android-liveness.md section 13.1.",
            "Pictures: Android capture-override-*.png; desktop none.",
        ],
    ),
]

H = HEAD_H + ROW_H * len(STATES) + 40
out.append(
    f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" role="img" '
    f'aria-labelledby="wf-title wf-desc">'
)
out.append('<title id="wf-title">Face liveness wireframes: desktop dialog and Android capture screen for each state</title>')
out.append(
    '<desc id="wf-desc">Fourteen rows, one per user-interface state of PS04 section 10 plus device error, multiple '
    'faces, offline and supervisor override. Each row shows the desktop dialog, the Android screen, the message '
    'key and notes. Drawn on an opaque light card so it reads the same on light and dark pages.</desc>'
)
out.append(
    '<defs><filter id="shadow" x="-10%" y="-10%" width="130%" height="140%">'
    '<feDropShadow dx="0" dy="2" stdDeviation="2.5" flood-color="#000000" flood-opacity="0.35"/></filter></defs>'
)
# Opaque card: the sheet looks the same on light and dark backgrounds.
rect(0, 0, W, H, "#F7F9FB", "#9AA5B1", 2, 12)
t(24, 40, "Face liveness wireframes: desktop dialog and Android capture screen", 24, "#0B2E4F", "bold")
t(
    24, 66,
    "One row per state of PS04 section 10 (UI/UX) and four team states. Texts are the English strings of docs/ux/message-catalogue.csv; "
    "states, icons and colours follow LivenessUiStateMapper,",
    12.5,
)
t(
    24, 84,
    "which both clients use. Drawn 2026-10-04. These are wireframes, not screenshots: real screens are in docs/ux/screenshots/ "
    "(file names in the last column). Grey shapes stand for the camera image.",
    12.5,
)
legend(24, 102)
t(24, 140, "Desktop: 520 px dialog over the face capture screen (drawn at about 48 %). Android: full-screen capture screen on a 1280x800 tablet (about 37.5 %). Text is enlarged for reading.", 12.5)
cols = [(24, "State, PS04 source, message key"), (344, "Desktop Registration Client"), (620, "Android Registration Client"), (1124, "Notes and committed pictures")]
for cx, label in cols:
    t(cx, 182, label, 14, "#0B2E4F", "bold")
out.append(f'<line x1="24" y1="{HEAD_H-8}" x2="{W-24}" y2="{HEAD_H-8}" stroke="#9AA5B1" stroke-width="1.5"/>')

for i, s in enumerate(STATES):
    y = HEAD_H + i * ROW_H
    if i % 2 == 1:
        rect(12, y - 4, W - 24, ROW_H, "#EEF2F6", rx=6)
    yy = tw(24, y + 22, s["name"], 290, 15, "#0B2E4F", "bold", 19)
    yy = tw(24, yy + 6, s["src"], 290, 11.5, "#3E4C59", lh=15)
    yy = tw(24, yy + 6, s["key"], 290, 10.5, "#3E4C59", lh=13.5, italic=True)
    desktop(344, y + 6, s["d"])
    android(620, y + 12, s["a"])
    ny = y + 22
    for n in s["notes"]:
        ny = tw(1124, ny, "– " + n, 290, 11, "#1F2933", lh=14.5) + 6

out.append("</svg>")
with open(OUT, "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(out) + "\n")
print("wrote", OUT, H)
