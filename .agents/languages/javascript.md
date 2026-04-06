# JavaScript And TypeScript

Load this file when changing `javascript/`.

## Rules

- Run JavaScript and TypeScript commands from within `javascript/`.
- This implementation uses npm or yarn for package management.

## Commands

```bash
# Install dependencies
npm install

# Run tests
node ./node_modules/.bin/jest --ci --reporters=default --reporters=jest-junit

# Lint TypeScript
git ls-files -- '*.ts' | xargs -P 5 node ./node_modules/.bin/eslint
```
