package buffeffects.hytale;

import buffeffects.core.commands.BfxCommands;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /bfx} (alias {@code /buffeffects}): hands everything after the command name to the core's
 * {@link BfxCommands}, which checks each subcommand's own permission. The command itself needs none, so
 * everyone can use {@code /bfx list}, {@code info} and {@code show}.
 */
final class BfxCommand extends CommandBase {
    private final Supplier<BfxCommands> commands;

    BfxCommand(Supplier<BfxCommands> commands) {
        super("bfx", "BuffEffects: list, inspect, apply, remove and cure status effects");
        this.commands = commands;
        addAliases("buffeffects");
        setAllowsExtraArguments(true);
        requireNoPermission();
    }

    @Override
    protected void executeSync(CommandContext ctx) {
        CommandSender sender = ctx.sender();
        BfxCommands c = commands.get();
        if (c == null) {
            ctx.sendMessage(Message.raw("[BuffEffects] still starting up"));
            return;
        }
        List<String> lines = c.run(sender.getUuid().toString(), sender.getUsername(), ArgLine.of(ctx.getInputString()));
        for (String l : lines) ctx.sendMessage(Message.raw(l));
    }
}
