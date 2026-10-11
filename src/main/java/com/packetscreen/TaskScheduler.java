package com.packetscreen;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;

final class TaskScheduler {

    private final Plugin plugin;
    private final boolean folia;
    private Object globalScheduler;
    private Method globalExecute;
    private Method entityScheduler;
    private Method entityExecute;
    private Method entityRepeat;
    private Method cancelTask;

    TaskScheduler(Plugin plugin) {
        this.plugin = plugin;
        this.folia = isFoliaServer();
        if (folia) {
            try {
                globalScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                Class<?> global = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
                globalExecute = global.getMethod("execute", Plugin.class, Runnable.class);
                Class<?> entity = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler");
                entityScheduler = Entity.class.getMethod("getScheduler");
                entityExecute = entity.getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class);
                entityRepeat = entity.getMethod("runAtFixedRate", Plugin.class, Consumer.class, Runnable.class, long.class, long.class);
                cancelTask = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask").getMethod("cancel");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unsupported Folia scheduler API", e);
            }
        }
    }

    static boolean isFoliaServer() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    boolean isFolia() {
        return folia;
    }

    void runGlobal(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        if (folia) {
            invoke(globalExecute, globalScheduler, plugin, task);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    void runFor(CommandSender sender, Runnable task) {
        if (sender instanceof Player) {
            runFor((Player) sender, task);
        } else {
            runGlobal(task);
        }
    }

    void runFor(Player player, Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        if (folia) {
            invoke(entityExecute, invoke(entityScheduler, player), plugin, task, null, 1L);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    Object repeatFor(Player player, Runnable task, long period) {
        Consumer<Object> consumer = scheduled -> task.run();
        return invoke(entityRepeat, invoke(entityScheduler, player), plugin, consumer, null, 1L, period);
    }

    Object repeatGlobal(Runnable task, long period) {
        return Bukkit.getScheduler().runTaskTimer(plugin, task, 1L, period);
    }

    void cancel(Object task) {
        if (task == null) {
            return;
        }
        if (task instanceof BukkitTask) {
            ((BukkitTask) task).cancel();
        } else if (cancelTask != null) {
            invoke(cancelTask, task);
        }
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException(cause);
        }
    }
}
