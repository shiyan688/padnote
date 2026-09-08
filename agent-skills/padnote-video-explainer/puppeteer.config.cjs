const path = require('node:path');

module.exports = {
  cacheDirectory: path.join(__dirname, '.local-cache', 'puppeteer'),
};
