from http.server import BaseHTTPRequestHandler, HTTPServer
import json, secrets, hmac
from pathlib import Path

HOST="127.0.0.1"
PORT=8765
STATE=Path.home()/".jarvis"
TOKEN_FILE=STATE/"pairing_token"

def get_token():
    STATE.mkdir(exist_ok=True)
    if TOKEN_FILE.exists():
        return TOKEN_FILE.read_text(encoding="utf-8").strip()
    token=secrets.token_urlsafe(32)
    TOKEN_FILE.write_text(token,encoding="utf-8")
    return token

TOKEN=get_token()

class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def reply(self,status,data):
        raw=json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type","application/json")
        self.send_header("Access-Control-Allow-Origin","*")
        self.send_header("Access-Control-Allow-Headers","Content-Type, X-JARVIS-PAIRING")
        self.end_headers()
        self.wfile.write(raw)
    def do_GET(self):
        paired=hmac.compare_digest(self.headers.get("X-JARVIS-PAIRING",""),TOKEN)
        self.reply(200,{"ok":True,"name":"JARVIS PC Companion","paired":paired})
    def do_POST(self):
        if not hmac.compare_digest(self.headers.get("X-JARVIS-PAIRING",""),TOKEN):
            return self.reply(401,{"error":"Pairing required."})
        self.reply(200,{"ok":True,"message":"Secure companion connection accepted.","capabilities":["status","pairing"]})

if __name__=="__main__":
    print("JARVIS PC Companion")
    print("Pairing code (keep private):")
    print(TOKEN)
    print("Listening only on 127.0.0.1:%d" % PORT)
    HTTPServer((HOST,PORT),Handler).serve_forever()
