package org.tron.plugins;

import picocli.CommandLine;

@CommandLine.Command(name = "db",
    mixinStandardHelpOptions = true,
    version = "db command 1.0",
    description = "An rich command set that provides high-level operations  for dbs.",
    subcommands = {CommandLine.HelpCommand.class,
        DbMove.class,
        DbArchive.class,
        DbConvert.class,
        DbLite.class,
        DbCopy.class,
        DbDebug.class,
        DbMinCompare.class,
        DbQueryProperties.class,
        DbCompact.class,
        DbAccountScan.class,
        DbBlockScan.class,
        DbAccountParser.class,
        DbTrim.class,
        DbBlockRetScan.class,
        DbBlockStats.class,
        DbTrim.class,
        DbRoot.class
    },
    commandListHeading = "%nCommands:%n%nThe most commonly used db commands are:%n"
)
public class Db {
}
