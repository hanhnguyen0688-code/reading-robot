"""Assemble app/index.html from the robot-screen design in web/index.html plus the app shell.

Run after editing web/index.html styles/markup:  python app/tools/build_index.py
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
web = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
shell = (ROOT / "app" / "tools" / "shell.html").read_text(encoding="utf-8")

css = re.search(r"<style>(.*?)</style>", web, re.S).group(1)
css = css.replace("body.demo #stage{top:calc(50% - 30px)}\n", "")
stage = re.search(r'(<div id="viewport">.*?)\n<div id="demo">', web, re.S).group(1)
# replace the LiveKit "start" screen with the app's home screen
stage = re.sub(r'  <!-- ============ start / kiosk ============ -->.*?</section>\n',
               '  <!--HOME-->\n', stage, flags=re.S)
script = re.search(r"<script>\n(.*?)/\* ======================= LIVE MODE", web, re.S).group(1)

out = (shell.replace("/*ROBOT_CSS*/", css)
            .replace("<!--STAGE-->", stage)
            .replace("/*RENDERER*/", script))
home = re.search(r"<!--HOME_SECTION-->(.*?)<!--/HOME_SECTION-->", out, re.S).group(1)
out = re.sub(r"<!--HOME_SECTION-->.*?<!--/HOME_SECTION-->", "", out, flags=re.S).replace("  <!--HOME-->\n", home)
(ROOT / "app" / "index.html").write_text(out, encoding="utf-8")
print("app/index.html", len(out), "bytes")
