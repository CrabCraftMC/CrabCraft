import winston from "winston";
import chalk from "chalk";

const logger = winston.createLogger({
  format: winston.format.combine(
    winston.format.timestamp({ format: "YYYY-MM-DD HH:mm:ss" }),
    winston.format.colorize(),
    winston.format.printf((info) => {
      const { timestamp, level, message, module } = info;
      return `${chalk.gray(`[${timestamp}]`)} ${level} ${chalk.blue(
        module ? module : "app"
      )}: ${message}`;
    })
  ),
  transports: [new winston.transports.Console({ level: "debug" })],
});

export default logger;
