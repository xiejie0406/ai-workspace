import argparse
import asyncio

from aipicture_runtime.config import get_settings


def main():
    parser = argparse.ArgumentParser(description="若依 AI 图片私有计算 Worker")
    parser.add_argument("mode", choices=["serve", "worker"], default="serve", nargs="?")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=None)
    args = parser.parse_args()
    if args.mode == "serve":
        import uvicorn
        uvicorn.run("aipicture_runtime.main:app", host=args.host, port=args.port or get_settings().api_port)
    else:
        from aipicture_runtime.execution import ExecutionClient
        async def run():
            client = ExecutionClient()
            try:
                await client.serve()
            finally:
                await client.close()
        asyncio.run(run())


if __name__ == "__main__":
    main()
