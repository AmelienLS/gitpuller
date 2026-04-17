package dev.neylz.gitpuller.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.neylz.gitpuller.util.GitUtil;
import dev.neylz.gitpuller.util.ModConfig;
import dev.neylz.gitpuller.util.TokenManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.WrongRepositoryStateException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class GitSyncCommand {
    private static final int SHORT_SHA_LENGTH = 7;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext, Commands.CommandSelection environment) {
        LiteralArgumentBuilder<CommandSourceStack> syncCommand = Commands.literal("sync").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

        if (!ModConfig.isMonoRepo()) {
            syncCommand = syncCommand.then(Commands.argument("pack name", StringArgumentType.word()).suggests(
                    (ctx, builder) -> SharedSuggestionProvider.suggest(GitUtil.getTrackedDatapacks(ctx.getSource().getServer().getWorldPath(LevelResource.DATAPACK_DIR).toFile()), builder))
                .executes((ctx) -> syncPack(ctx, StringArgumentType.getString(ctx, "pack name")))
            );
        } else {
            syncCommand = syncCommand.executes(GitSyncCommand::syncMonoPack);
        }

        dispatcher.register(Commands.literal("git").then(syncCommand));
    }

    private static int syncMonoPack(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        File repo = ctx.getSource().getServer().getWorldPath(LevelResource.DATAPACK_DIR).toFile();
        return syncRepo(ctx.getSource(), repo, "monorepo");
    }

    private static int syncPack(CommandContext<CommandSourceStack> ctx, String packName) throws CommandSyntaxException {
        File repo = new File(ctx.getSource().getServer().getWorldPath(LevelResource.DATAPACK_DIR).toFile(), packName);
        if (!repo.exists()) {
            throw new CommandSyntaxException(null, () -> "Datapack " + packName + " does not exist");
        }
        if (!GitUtil.isGitRepo(repo)) {
            throw new CommandSyntaxException(null, () -> "Datapack " + packName + " is not a git repository");
        }

        return syncRepo(ctx.getSource(), repo, "[" + packName + "]");
    }

    private static int syncRepo(CommandSourceStack source, File repoDir, String label) throws CommandSyntaxException {
        source.sendSuccess(
            () -> Component.empty()
                .append(Component.literal("Synchronizing ").withStyle(ChatFormatting.RESET))
                .append(Component.literal(label).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" with remote (transactional mode)").withStyle(ChatFormatting.RESET)),
            true
        );

        MinecraftServer server = source.getServer();
        String previousSha = GitUtil.getCurrentHeadSha1(repoDir, 40);
        String newSha;

        try (Git git = Git.open(repoDir)) {
            UsernamePasswordCredentialsProvider credentialsProvider =
                new UsernamePasswordCredentialsProvider(TokenManager.getInstance().getToken(), "");

            git.fetch()
                .setRemoveDeletedRefs(true)
                .setCredentialsProvider(credentialsProvider)
                .call();

            PullResult pullResult = git.pull()
                .setRebase(true)
                .setCredentialsProvider(credentialsProvider)
                .call();

            if (!pullResult.isSuccessful()) {
                throw new CommandSyntaxException(null, () -> "Pull operation failed on " + label + ".");
            }

            newSha = GitUtil.getCurrentHeadSha1(repoDir, 40);
        } catch (WrongRepositoryStateException | RefNotFoundException | CheckoutConflictException e) {
            rollbackOrThrow(repoDir, previousSha, "Repository state prevents sync on " + label + ": " + e.getMessage());
            return 0;
        } catch (IOException | GitAPIException e) {
            rollbackOrThrow(repoDir, previousSha, "Sync failed on " + label + ": " + e.getMessage());
            return 0;
        }

        if (newSha.equals(previousSha)) {
            source.sendSuccess(
                () -> Component.empty()
                    .append(Component.literal(label + " is already up to date").withStyle(ChatFormatting.GREEN)),
                true
            );
            return 1;
        }

        source.sendSuccess(
            () -> Component.empty()
                .append(Component.literal("Git update applied to ").withStyle(ChatFormatting.RESET))
                .append(Component.literal(label).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(", validating datapacks...").withStyle(ChatFormatting.RESET)),
            true
        );

        if (!reloadDatapacks(server)) {
            source.sendSuccess(
                () -> Component.empty()
                    .append(Component.literal("Validation failed on new revision, rolling back ").withStyle(ChatFormatting.RED))
                    .append(Component.literal(label).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal("...").withStyle(ChatFormatting.RED)),
                true
            );

            rollbackOrThrow(repoDir, previousSha, "Failed to restore " + label + " to previous revision");

            if (!reloadDatapacks(server)) {
                throw new CommandSyntaxException(null, () -> "Rollback restored Git commit, but datapack reload still failed. Manual intervention required.");
            }

            source.sendSuccess(
                () -> Component.empty()
                    .append(Component.literal("Sync rejected; restored ").withStyle(ChatFormatting.RED))
                    .append(Component.literal(label).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(" to ").withStyle(ChatFormatting.RED))
                    .append(Component.literal(shortSha(previousSha)).withStyle(ChatFormatting.AQUA)),
                true
            );
            return 0;
        }

        source.sendSuccess(
            () -> Component.empty()
                .append(Component.literal("Synchronized ").withStyle(ChatFormatting.RESET))
                .append(Component.literal(label).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" (").withStyle(ChatFormatting.RESET))
                .append(Component.literal(shortSha(previousSha)).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" -> ").withStyle(ChatFormatting.RESET))
                .append(Component.literal(shortSha(newSha)).withStyle(ChatFormatting.LIGHT_PURPLE))
                .append(Component.literal(")").withStyle(ChatFormatting.RESET)),
            true
        );
        return 1;
    }

    private static void rollbackOrThrow(File repoDir, String previousSha, String reason) throws CommandSyntaxException {
        if (previousSha == null || previousSha.isEmpty()) {
            throw new CommandSyntaxException(null, () -> reason + " (rollback unavailable: missing previous commit)");
        }

        try (Git git = Git.open(repoDir)) {
            git.reset()
                .setMode(ResetCommand.ResetType.HARD)
                .setRef(previousSha)
                .call();
        } catch (IOException | GitAPIException rollbackError) {
            throw new CommandSyntaxException(null, () -> reason + " (rollback failed: " + rollbackError.getMessage() + ")");
        }
    }

    private static boolean reloadDatapacks(MinecraftServer server) {
        try {
            CompletableFuture<Void> future = server.reloadResources(server.getPackRepository().getSelectedIds());
            future.get();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return false;
        }
    }

    private static String shortSha(String sha) {
        if (sha == null || sha.isEmpty()) {
            return "unknown";
        }
        return sha.substring(0, Math.min(SHORT_SHA_LENGTH, sha.length()));
    }
}
