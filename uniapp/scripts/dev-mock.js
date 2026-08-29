const { spawn } = require('child_process')

const args = process.argv.slice(2)
const quoteArg = (value) => {
  const text = String(value)
  return /[\s"&|<>^]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}
const command = process.platform === 'win32' ? (process.env.ComSpec || 'cmd.exe') : 'uni'
const commandArgs = process.platform === 'win32'
  ? ['/d', '/s', '/c', `uni ${args.map(quoteArg).join(' ')}`]
  : args

const child = spawn(command, commandArgs, {
  stdio: 'inherit',
  env: {
    ...process.env,
    MOCK_DEV: 'true'
  }
})

child.on('exit', (code) => {
  process.exit(code || 0)
})

child.on('error', (error) => {
  console.error(error.message)
  process.exit(1)
})
