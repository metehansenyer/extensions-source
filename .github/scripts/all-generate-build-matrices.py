import itertools
import json
import os
import sys
from pathlib import Path
from typing import NoReturn


def get_all_modules() -> tuple[list[str], list[str]]:
    modules = []
    deleted = []
    for lang in Path("src").iterdir():
        if not lang.is_dir():
            continue
        for extension in lang.iterdir():
            if not extension.is_dir():
                continue
            modules.append(f":src:{lang.name}:{extension.name}")
            deleted.append(f"{lang.name}.{extension.name}")
    return modules, deleted


def main() -> NoReturn:
    _, build_type = sys.argv
    modules, deleted = get_all_modules()

    chunked = {
        "chunk": [
            {"number": i + 1, "modules": modules}
            for i, modules in
            enumerate(itertools.batched(
                map(lambda x: f"{x}:assemble{build_type}", modules),
                int(os.getenv("CI_CHUNK_SIZE", 65))
            ))
        ]
    }

    print(f"Module chunks to build:\n{json.dumps(chunked, indent=2)}\n\nModule to delete:\n{json.dumps(deleted, indent=2)}")

    if os.getenv("CI") == "true":
        with open(os.getenv("GITHUB_OUTPUT"), 'a') as out_file:
            out_file.write(f"matrix={json.dumps(chunked)}\n")
            out_file.write(f"delete={json.dumps(deleted)}\n")

if __name__ == '__main__':
    main()
