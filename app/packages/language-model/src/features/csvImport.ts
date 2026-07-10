import type { ModelData, ModelDataInstance, ModelDataPropertyValue } from "./modelData.js";

export interface CsvImportEntry {
    className: string;
    csvText: string;
}

export interface CsvImportResult {
    instances: ModelDataInstance[];
    links: ModelData["links"];
    warnings: string[];
}

/**
 * Maps one or more CSV files onto a metamodel, producing model instances and links.
 * Each entry maps one class to one CSV file (one row = one instance).
 * Column names must match property names in the metamodel class.
 * Cross-object references use a reserved `_id` column as the row identifier,
 * with reference columns holding the target `_id` value.
 */
export function importCsvEntries(
    entries: CsvImportEntry[],
    metamodelClasses: MetamodelClassInfo[]
): CsvImportResult {
    const warnings: string[] = [];
    const allInstances: ModelDataInstance[] = [];
    const allLinks: ModelData["links"] = [];

    const idMap = new Map<string, string>();

    for (const entry of entries) {
        const classInfo = metamodelClasses.find(c => c.name === entry.className);
        if (!classInfo) {
            warnings.push(`Class '${entry.className}' not found in metamodel — skipping.`);
            continue;
        }

        const rows = parseCsv(entry.csvText);
        if (rows.length < 2) {
            warnings.push(`CSV for class '${entry.className}' has no data rows — skipping.`);
            continue;
        }

        const header = rows[0];
        const dataRows = rows.slice(1);
        const idColIndex = header.indexOf("_id");

        const propertyNames = new Set(classInfo.properties.map(p => p.name));
        const unknownCols = header.filter(h => h !== "_id" && !propertyNames.has(h));
        if (unknownCols.length > 0) {
            warnings.push(`CSV for '${entry.className}' has unknown columns: ${unknownCols.join(", ")} — they will be ignored.`);
        }

        dataRows.forEach((row, rowIndex) => {
            const normalizedRow = normalizeRow(row, header.length, rowIndex + 2, warnings);
            const instanceName = `${entry.className}_${rowIndex}`;

            if (idColIndex >= 0) {
                const idValue = normalizedRow[idColIndex];
                if (idValue) {
                    idMap.set(`${entry.className}:${idValue}`, instanceName);
                }
            }

            const properties: Record<string, ModelDataPropertyValue> = {};
            header.forEach((colName, colIndex) => {
                if (colName === "_id") return;
                const prop = classInfo.properties.find(p => p.name === colName);
                if (!prop) return;
                properties[colName] = convertCellValue(normalizedRow[colIndex], prop);
            });

            allInstances.push({
                name: instanceName,
                className: entry.className,
                properties
            });
        });
    }

    for (const entry of entries) {
        const classInfo = metamodelClasses.find(c => c.name === entry.className);
        if (!classInfo) continue;

        const rows = parseCsv(entry.csvText);
        if (rows.length < 2) continue;

        const header = rows[0];
        const dataRows = rows.slice(1);

        const refCols = header.filter(colName => {
            const prop = classInfo.properties.find(p => p.name === colName);
            return prop?.isReference;
        });

        if (refCols.length === 0) continue;

        dataRows.forEach((row, rowIndex) => {
            const normalizedRow = normalizeRow(row, header.length, rowIndex + 2, warnings);
            const sourceInstanceName = `${entry.className}_${rowIndex}`;

            refCols.forEach(colName => {
                const prop = classInfo.properties.find(p => p.name === colName)!;
                const rawValue = normalizedRow[header.indexOf(colName)];
                if (!rawValue) return;

                const targetIds = rawValue.split(";").map(s => s.trim()).filter(Boolean);
                targetIds.forEach(targetId => {
                    const targetInstanceName = idMap.get(`${prop.referencedClass}:${targetId}`);
                    if (!targetInstanceName) {
                        warnings.push(`Reference '${targetId}' in column '${colName}' of '${entry.className}' row ${rowIndex + 2} could not be resolved.`);
                        return;
                    }
                    allLinks.push({
                        sourceName: sourceInstanceName,
                        sourceProperty: colName,
                        targetName: targetInstanceName,
                        targetProperty: null
                    });
                });
            });
        });
    }

    return { instances: allInstances, links: allLinks, warnings };
}

export interface MetamodelPropertyInfo {
    name: string;
    type: "string" | "int" | "long" | "double" | "float" | "boolean" | "enum" | "reference";
    enumEntries?: string[];
    isReference?: boolean;
    referencedClass?: string;
}

export interface MetamodelClassInfo {
    name: string;
    properties: MetamodelPropertyInfo[];
}

function convertCellValue(rawValue: string, prop: MetamodelPropertyInfo): ModelDataPropertyValue {
    if (!rawValue || rawValue.trim() === "") return null;
    const v = rawValue.trim();
    switch (prop.type) {
        case "int":
        case "long": {
            const n = parseInt(v, 10);
            return isNaN(n) ? v : n;
        }
        case "double":
        case "float": {
            const n = parseFloat(v);
            return isNaN(n) ? v : n;
        }
        case "boolean":
            return v.toLowerCase() === "true";
        case "enum":
            return { enum: v };
        default:
            return v;
    }
}

function normalizeRow(row: string[], expectedLength: number, rowNumber: number, warnings: string[]): string[] {
    if (row.length === expectedLength) return row;
    if (row.length < expectedLength) {
        warnings.push(`Row ${rowNumber} has fewer columns than the header; missing values treated as blank.`);
        return [...row, ...Array(expectedLength - row.length).fill("")];
    }
    warnings.push(`Row ${rowNumber} has more columns than the header; extra values ignored.`);
    return row.slice(0, expectedLength);
}

function parseCsv(text: string): string[][] {
    const rows: string[][] = [];
    let currentRow: string[] = [];
    let currentField = "";
    let inQuotes = false;
    let i = 0;

    const endField = () => {
        currentRow.push(currentField);
        currentField = "";
    };

    const endRow = () => {
        endField();
        if (!(currentRow.length === 1 && currentRow[0].trim() === "")) {
            rows.push(currentRow);
        }
        currentRow = [];
    };

    while (i < text.length) {
        const c = text[i];
        if (inQuotes) {
            if (c === '"') {
                if (i + 1 < text.length && text[i + 1] === '"') {
                    currentField += '"';
                    i++;
                } else {
                    inQuotes = false;
                }
            } else {
                currentField += c;
            }
        } else {
            if (c === '"') {
                inQuotes = true;
            } else if (c === ',') {
                endField();
            } else if (c === '\r') {
                // skip
            } else if (c === '\n') {
                endRow();
            } else {
                currentField += c;
            }
        }
        i++;
    }

    if (currentField !== "" || currentRow.length > 0) {
        endRow();
    }

    return rows;
}
