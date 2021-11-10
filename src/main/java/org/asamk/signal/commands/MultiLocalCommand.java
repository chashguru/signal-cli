package org.asamk.signal.commands;

import net.sourceforge.argparse4j.inf.Namespace;

import org.asamk.signal.OutputWriter;
import org.asamk.signal.commands.exceptions.CommandException;

public interface MultiLocalCommand extends LocalCommand {

    void handleCommand(
            Namespace ns, SignalCreator c, OutputWriter outputWriter
    ) throws CommandException;
}
