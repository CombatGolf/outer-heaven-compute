#!/usr/bin/env python3
import argparse
import concurrent.futures
import hashlib
import json
import multiprocessing
import os
import platform
import re
import shutil
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

VERSION = "2.0.0"
CONFIG = Path.home() / ".config/outer-heaven-compute/config.json"
MAX_FILE = 50 * 1024 * 1024


def load_config():
    if not CONFIG.exists():
        raise RuntimeError("Celular não configurado. Use: ohc.py configure IP TOKEN")
    data = json.loads(CONFIG.read_text())
    if not data.get("host") or not data.get("token"):
        raise RuntimeError("Configuração incompleta.")
    return data["host"], data["token"]


def request(host, token, path, method="GET", data=None, timeout=120):
    url = f"http://{host}:8765{path}"
    req = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={
            "X-Token": token,
            "Content-Type": "application/octet-stream",
            "Connection": "close",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return response.read()
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {e.code}: {detail}") from None
    except urllib.error.URLError as e:
        raise RuntimeError(f"Falha de conexão: {e.reason}") from None


def local_info():
    import multiprocessing
    import shutil

    return {
        "device": platform.node(),
        "system": platform.system(),
        "architecture": platform.machine(),
        "cpu_threads": os.cpu_count() or 1,
        "ram_total_mb": round(
            os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES") / 1048576
        ) if hasattr(os, "sysconf") else None,
        "disk_free_gb": round(shutil.disk_usage(Path.home()).free / 1073741824, 2),
    }


def hash_worker(seconds):
    deadline = time.monotonic() + seconds
    digest = hashlib.sha256()
    data = bytes(1024)
    count = 0

    while time.monotonic() < deadline:
        digest.update(data)
        data = digest.digest()
        count += 1

    return count


def local_benchmark(seconds, workers):
    start = time.monotonic()

    with concurrent.futures.ProcessPoolExecutor(
        max_workers=workers
    ) as pool:
        jobs = [pool.submit(hash_worker, seconds) for _ in range(workers)]
        operations = sum(job.result(timeout=seconds + 20) for job in jobs)

    elapsed = time.monotonic() - start

    return {
        "device": platform.node(),
        "workers": workers,
        "operations": operations,
        "elapsed_seconds": round(elapsed, 3),
        "operations_per_second": round(operations / elapsed),
    }


def remote_benchmark(host, token, seconds, workers):
    path = f"/compute?seconds={seconds}&workers={workers}"
    result = json.loads(request(host, token, path, timeout=seconds + 30))
    elapsed = result["elapsed_ms"] / 1000

    result["operations_per_second"] = round(
        result["operations"] / max(elapsed, 0.001)
    )
    result["device"] = "Android"
    return result


def distributed_benchmark(host, token, seconds, remote_workers, local_workers):
    print("Iniciando processamento distribuído...")
    print(f"PC: {local_workers} processos")
    print(f"Moto G22: até {remote_workers} workers")
    print("Os dois dispositivos trabalharão simultaneamente.\n")

    start = time.monotonic()

    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as network:
        remote_job = network.submit(
            remote_benchmark, host, token, seconds, remote_workers
        )

        time.sleep(0.15)
        local_result = local_benchmark(seconds, local_workers)
        remote_result = remote_job.result()

    elapsed = time.monotonic() - start
    total = local_result["operations"] + remote_result["operations"]

    return {
        "mode": "DISTRIBUTED",
        "duration_seconds": round(elapsed, 3),
        "local_pc": local_result,
        "remote_android": remote_result,
        "combined_operations": total,
        "combined_operations_per_second": round(total / elapsed),
        "note": (
            "Benchmark SHA-256 distribuído. Mede esta tarefa específica; "
            "não representa aceleração universal de programas."
        ),
    }


def safe_filename(name):
    return bool(
        name
        and re.fullmatch(r"[A-Za-z0-9._-]{1,80}", name)
        and name not in (".", "..")
    )


def command_configure(args):
    CONFIG.parent.mkdir(parents=True, exist_ok=True)
    temporary = CONFIG.with_suffix(".tmp")
    temporary.write_text(json.dumps({
        "host": args.host,
        "token": args.token,
    }))
    temporary.chmod(0o600)
    temporary.replace(CONFIG)
    print("Celular configurado.")
    print(f"Endereço: {args.host}:8765")


def command_status(host, token):
    print("========== PC ==========")
    print(json.dumps(local_info(), indent=2, ensure_ascii=False))
    print("\n========== MOTO G22 ==========")
    remote = json.loads(request(host, token, "/status"))
    print(json.dumps(remote, indent=2, ensure_ascii=False))


def command_files(host, token):
    result = json.loads(request(host, token, "/files"))
    files = result.get("files", [])

    if not files:
        print("Nenhum arquivo compartilhado.")
        return

    print(f"{'ARQUIVO':40} {'TAMANHO':>15}")
    print("-" * 58)

    for item in files:
        print(f"{item['name']:40} {item['bytes']:>12,} bytes")


def command_upload(host, token, args):
    source = Path(args.file).expanduser()

    if not source.is_file():
        raise RuntimeError(f"Arquivo não encontrado: {source}")

    if source.stat().st_size > MAX_FILE:
        raise RuntimeError("Limite de transferência: 50 MB.")

    name = args.name or source.name

    if not safe_filename(name):
        raise RuntimeError("Nome inválido. Use letras, números, ponto, _ ou -.")

    data = source.read_bytes()
    path = "/upload?name=" + urllib.parse.quote(name, safe="")

    result = request(host, token, path, "POST", data)

    print(result.decode("utf-8", errors="replace"))
    print("Transferência concluída.")


def command_download(host, token, args):
    if not safe_filename(args.name):
        raise RuntimeError("Nome de arquivo inválido.")

    path = "/download?name=" + urllib.parse.quote(args.name, safe="")
    data = request(host, token, path)

    if len(data) > MAX_FILE:
        raise RuntimeError("O arquivo recebido ultrapassou 50 MB.")

    output = Path(args.output or args.name).expanduser()
    output.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.NamedTemporaryFile(
        dir=output.parent, delete=False
    ) as temp:
        temp.write(data)
        temp_path = Path(temp.name)

    temp_path.replace(output)

    print(f"Arquivo salvo: {output}")
    print(f"Tamanho: {len(data):,} bytes")
    print(f"SHA-256: {hashlib.sha256(data).hexdigest()}")


def main():
    parser = argparse.ArgumentParser(
        description="Outer Heaven Compute v2"
    )
    parser.add_argument("--version", action="version", version=VERSION)

    sub = parser.add_subparsers(dest="command", required=True)

    cfg = sub.add_parser("configure")
    cfg.add_argument("host")
    cfg.add_argument("token")

    sub.add_parser("status")
    sub.add_parser("doctor")
    sub.add_parser("files")

    bench = sub.add_parser("benchmark")
    bench.add_argument("--seconds", type=int, default=10)
    bench.add_argument("--workers", type=int, default=8)
    bench.add_argument("--local-workers", type=int, default=0)
    bench.add_argument(
        "--mode",
        choices=["local", "remote", "distributed"],
        default="distributed",
    )

    upload = sub.add_parser("upload")
    upload.add_argument("file")
    upload.add_argument("--name")

    download = sub.add_parser("download")
    download.add_argument("name")
    download.add_argument("--output")

    args = parser.parse_args()

    try:
        if args.command == "configure":
            command_configure(args)
            return

        if args.command == "benchmark":
            seconds = max(1, min(30, args.seconds))
            remote_workers = max(1, min(8, args.workers))
            local_workers = args.local_workers or min(os.cpu_count() or 1, 2)
            local_workers = max(1, min(8, local_workers))

            if args.mode == "local":
                result = local_benchmark(seconds, local_workers)
            else:
                host, token = load_config()

                if args.mode == "remote":
                    result = remote_benchmark(
                        host, token, seconds, remote_workers
                    )
                else:
                    result = distributed_benchmark(
                        host, token, seconds,
                        remote_workers, local_workers
                    )

            print(json.dumps(result, indent=2, ensure_ascii=False))
            return

        host, token = load_config()

        if args.command == "status":
            command_status(host, token)

        elif args.command == "doctor":
            print("Outer Heaven Compute", VERSION)
            print("Python:", sys.version.split()[0])
            print("Configuração:", "OK" if CONFIG.exists() else "AUSENTE")
            print("PC:", platform.node())
            print("CPU:", os.cpu_count(), "threads")

            remote = json.loads(request(host, token, "/status"))
            print("Celular:", remote.get("device"))
            print("Android:", remote.get("android"))
            print("CPU celular:", remote.get("cpu_threads"))
            print("Conexão: OK")

        elif args.command == "files":
            command_files(host, token)

        elif args.command == "upload":
            command_upload(host, token, args)

        elif args.command == "download":
            command_download(host, token, args)

    except KeyboardInterrupt:
        print("\nOperação cancelada.")
        raise SystemExit(130)

    except Exception as error:
        print(f"\nERRO: {error}", file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    multiprocessing.freeze_support()
    main()
