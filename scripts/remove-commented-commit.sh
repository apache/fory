#!/bin/bash
# remove-commented-commit.sh
# Remove commented lines (# or <!-- -->) from Git commit messages
# Usage: ./remove-commented-commit.sh COMMIT_MSG_FILE

COMMIT_MSG_FILE="$1"

if [ ! -f "$COMMIT_MSG_FILE" ]; then
    echo "Commit message file not found!"
    exit 1
fi

# Remove lines starting with '#' (Git comments)
sed -i '/^#/d' "$COMMIT_MSG_FILE"

# Remove HTML-style comment blocks (<!-- -->)
sed -i '/^<!--/,/-->$/d' "$COMMIT_MSG_FILE"
