#!/usr/bin/env python3
import argparse
import json
import urllib.request
import urllib.parse
from pathlib import Path

CONFIG = Path.home() / ".config/outer-heaven-compute/config.json"

def request(host, token, path, method="GET", data=None):
    url = f"http://{host}:8765{path}"
    req = urllib.request.Request(
        url, data=data, method=method,
        headers={"X-Token": token, "Content-Type": "application/octet-stream"}
    )
    with urllib.request.urlopen(req, timeout=120) as response:
        return response.read()

def main():
    parser = argparse.ArgumentParser(description="Outer Heaven Compute Linux Client")
    sub = parser.add_subparsers(dest="command", required=True)

    cfg = sub.add_parser("configure", help="Configurar o celular")
    cfg.add_argument("host")
    cfg.add_argument("token")

    sub.add_parser("status", help="Ver recursos do celular")
    sub.add_parser("files", help="Listar arquivos compartilhados")

    bench = sub.add_parser("benchmark", help="Testar processamento remoto")
    bench.add_argument("--seconds", type=int, default=5)
    bench.add_argument("--workers", type=int, default=8)

    upload = sub.add_parser("upload", help="Enviar arquivo ao celular")
    upload.add_argument("file")
    upload.add_argument("--name")

    download = sub.add_parser("download", help="Baixar arquivo do celular")
    download.add_argument("name")
    download.add_argument("--output")

    args = parser.parse_args()

    if args.command == "configure":
        CONFIG.parent.mkdir(parents=True, exist_ok=True)
        CONFIG.write_text(json.dumps({"host": args.host, "token": args.token}))
        CONFIG.chmod(0o600)
        print("Celular configurado.")
        return

    if not CONFIG.exists():
        parser.error("Configure primeiro: ohc.py configure IP TOKEN")

    config = json.loads(CONFIG.read_text())
    host, token = config["host"], config["token"]

    try:
        if args.command == "status":
            data = request(host, token, "/status")
            print(json.dumps(json.loads(data), indent=2, ensure_ascii=False))

        elif args.command == "files":
            data = request(host, token, "/files")
            print(json.dumps(json.loads(data), indent=2, ensure_ascii=False))

        elif args.command == "benchmark":
            seconds = max(1, min(30, args.seconds))
            workers = max(1, min(8, args.workers))
            path = f"/compute?seconds={seconds}&workers={workers}"
            data = request(host, token, path)
            print(json.dumps(json.loads(data), indent=2, ensure_ascii=False))

        elif args.command == "upload":
            source = Path(args.file).expanduser()
            name = args.name or source.name
            data = source.read_bytes()
            path = "/upload?name=" + urllib.parse.quote(name, safe="")
            result = request(host, token, path, "POST", data)
            print(result.decode())

        elif args.command == "download":
            name = args.name
            path = "/download?name=" + urllib.parse.quote(name, safe="")
            data = request(host, token, path)
            output = Path(args.output or name).expanduser()
            output.write_bytes(data)
            print(f"Salvo em: {output} ({len(data)} bytes)")

    except Exception as error:
        print("Erro:", error)
        raise SystemExit(1)

if __name__ == "__main__":
    main()
