import itertools
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import NoReturn

def run_command(command: str) -> str:
    result = subprocess.run(command, capture_output=True, text=True, shell=True)
    if result.returncode != 0:
        print(result.stderr.strip())
        sys.exit(result.returncode)
    return result.stdout.strip()

def get_all_modules_with_libs() -> tuple[list[str], list[str]]:
    modules = []
    deleted = []
    libs = []

    for lang in Path("src").iterdir():
        if lang.is_dir():
            for extension in lang.iterdir():
                if extension.is_dir():
                    modules.append(f":src:{lang.name}:{extension.name}")
                    deleted.append(f"{lang.name}.{extension.name}")

    for multisrc in Path("lib-multisrc").iterdir():
        if multisrc.is_dir():
            libs.append(f":lib-multisrc:{multisrc.name}:printDependentExtensions")

    for lib in Path("lib").iterdir():
        if lib.is_dir():
            libs.append(f":lib:{lib.name}:printDependentExtensions")

    return modules, libs, deleted

def get_dependent_modules(libs: list[str]) -> list[str]:
    if len(libs) == 0:
        return []

    modules = []
    try:
        dependent_output = run_command("./gradlew -q " + " ".join(libs))
        for module in dependent_output.splitlines():
            if module.strip():
                modules.append(module.strip())
    except Exception as e:
        print(f"Warning: Could not get dependent modules: {e}")

    return modules

def main() -> NoReturn:
    _, build_type = sys.argv

    all_modules, libs, deleted = get_all_modules_with_libs()

    dependent_modules = get_dependent_modules(libs)
    all_modules.extend(dependent_modules)

    all_modules = list(set(all_modules))

    chunked = {
        "chunk": [
            {"number": i + 1, "modules": modules}
            for i, modules in
            enumerate(itertools.batched(
                map(lambda x: f"{x}:assemble{build_type}", all_modules),
                int(os.getenv("CI_CHUNK_SIZE", 65))
            ))
        ]
    }

    print(f"All modules to build ({len(all_modules)} total):\n{json.dumps(chunked, indent=2)}\n\nModules to delete:\n{json.dumps(deleted, indent=2)}")

    if os.getenv("CI") == "true":
        with open(os.getenv("GITHUB_OUTPUT"), 'a') as out_file:
            out_file.write(f"matrix={json.dumps(chunked)}\n")
            out_file.write(f"delete={json.dumps(deleted)}\n")

if __name__ == '__main__':
    main()
