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
        DbRewardScan.class,
        DbBlockRewardScan.class,
        DbRewardCacheScan.class,
        DbTmpCompare.class,
        DbRewardMissScan.class,
        DbRewardCheck.class,
        DbRewardCacheScan2.class,
        DbRewardFastScan.class,
        DbRewardDebugNile.class,
        DbBlockMiss.class,
        DbOldRewardVi.class,
        DbRoot.class
    },
    commandListHeading = "%nCommands:%n%nThe most commonly used db commands are:%n"
)
public class Db {
}
