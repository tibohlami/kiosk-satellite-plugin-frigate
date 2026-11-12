#!/usr/bin/env python3
"""Build runner forwarding to official tools/build.py."""
import subprocess
import sys
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.exit(subprocess.run([sys.executable, os.path.join(ROOT, "tools", "build.py")] + sys.argv[1:]).returncode)
