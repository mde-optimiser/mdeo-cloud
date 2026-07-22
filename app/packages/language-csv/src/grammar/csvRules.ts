import { createTerminal, createRule, WS } from "@mdeo/language-common";
import { CsvFile } from "./csvTypes.js";

export const ANY_TEXT = createTerminal("ANY_TEXT").as(/[\s\S]+/);

export const CsvFileRule = createRule("CsvFileRule")
    .returns(CsvFile)
    .as(({ set }) => [set("content", ANY_TEXT)]);

export const CsvTerminals = [WS, ANY_TEXT];
