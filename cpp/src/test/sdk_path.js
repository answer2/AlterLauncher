// Print the SDK root as a forward-slash path, read from local.properties.
// Node is used because local.properties stores the Windows path in Java
// properties form (D\:\\android\\Sdk) and both sed and inline shell quoting
// mangle those backslashes; a script file keeps them literal.
const fs = require('fs');
const path = process.argv[2] || 'local.properties';
let raw = '';
try {
  const text = fs.readFileSync(path, 'utf8');
  const m = text.match(/^sdk\.dir=(.*)$/im);
  if (m) raw = m[1].replace(/\r$/, '');
} catch (e) {
  raw = '';
}
// Java-properties unescape: a backslash escapes the character after it.
let win = raw.replace(/\\(.)/g, '$1');
// Windows -> POSIX: swap separators, turn the drive letter into a /x/ root so
// Git Bash and the NDK toolchain paths resolve identically.
let posix = win.replace(/\\/g, '/');
posix = posix.replace(/^([A-Za-z]):/, (mm, d) => '/' + d.toLowerCase());
if (process.argv[3] === '--win') posix = win;
process.stdout.write(posix);
