#!/bin/bash

command -v remark >/dev/null 2>&1 || { echo >&2 "Package 'remark' not installed.  Aborting."; exit 1; }

LINT_OUTPUT=`remark -u validate-links -u lint-no-dead-urls . 2>&1`

LINT_OUTPUT_MODIFIED=`printf '%s\n' "${LINT_OUTPUT[@]}" | grep -v 'localhost\|warnings'`

if echo $LINT_OUTPUT_MODIFIED | grep -i "warning"; then
    printf '%s\n' "${LINT_OUTPUT_MODIFIED[@]}"
    echo ""
    echo "Please Fix the above broken links! Please ignore any localhost warnings."
    exit 1
else
    echo "No Issues Found!!"
fi
