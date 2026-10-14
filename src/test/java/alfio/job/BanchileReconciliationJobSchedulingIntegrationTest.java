/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.job;

import alfio.TestConfiguration;
import alfio.config.DataSourceConfiguration;
import alfio.config.Initializer;
import alfio.test.util.AlfioIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.config.FixedRateTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.Task;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El cron de reconciliación de Banchile ({@link BanchileReconciliationJob}) nunca corrió en
 * producción: era un {@code @Component} del paquete {@code alfio.job}, que ningún
 * {@code @ComponentScan} incluye.
 *
 * <p>Este test levanta el contexto con los jobs habilitados, como en producción (sin el perfil
 * {@code disable-jobs} que usan los demás tests de integración), y verifica que el cron quede
 * registrado como bean y como tarea programada del scheduler de Spring.
 */
@AlfioIntegrationTest
@ContextConfiguration(classes = {DataSourceConfiguration.class, TestConfiguration.class})
@ActiveProfiles({Initializer.PROFILE_DEV, Initializer.PROFILE_INTEGRATION_TEST})
// con los jobs habilitados el scheduler queda ejecutando tareas en segundo plano:
// se cierra el contexto al terminar para que no siga corriendo durante los demás tests
@DirtiesContext
class BanchileReconciliationJobSchedulingIntegrationTest {

    @Autowired
    private ConfigurableApplicationContext applicationContext;

    @Test
    void reconciliationJobIsRegisteredAsBean() {
        assertEquals(1, applicationContext.getBeanNamesForType(BanchileReconciliationJob.class).length,
            "BanchileReconciliationJob no está registrado como bean");
    }

    @Test
    void reconciliationJobIsScheduledEveryTwelveMinutes() {
        var tasks = scheduledTasksOf(BanchileReconciliationJob.class);
        assertEquals(1, tasks.size(), "BanchileReconciliationJob no está registrado como tarea programada");
        var task = assertInstanceOf(FixedRateTask.class, tasks.get(0));
        assertEquals(Duration.ofMinutes(12), task.getIntervalDuration());
    }

    @Test
    void reconciliationJobWaitsTwoMinutesAfterStartup() {
        // sin espera inicial el primer run ocurre en t=0 del arranque, sin ventana para observarlo
        var tasks = scheduledTasksOf(BanchileReconciliationJob.class);
        assertEquals(1, tasks.size(), "BanchileReconciliationJob no está registrado como tarea programada");
        var task = assertInstanceOf(FixedRateTask.class, tasks.get(0));
        assertEquals(Duration.ofMinutes(2), task.getInitialDelayDuration());
    }

    @Test
    void reconciliationJobDependsOnMigrator() {
        var beanFactory = applicationContext.getBeanFactory();
        var beanNames = beanFactory.getBeanNamesForType(BanchileReconciliationJob.class);
        assertEquals(1, beanNames.length, "BanchileReconciliationJob no está registrado como bean");
        var dependsOn = beanFactory.getBeanDefinition(beanNames[0]).getDependsOn();
        assertNotNull(dependsOn, "BanchileReconciliationJob no declara dependencias");
        assertTrue(Arrays.asList(dependsOn).contains("migrator"),
            "BanchileReconciliationJob debe crearse después de las migraciones (migrator), como Jobs");
    }

    private List<Task> scheduledTasksOf(Class<?> jobClass) {
        return applicationContext.getBeansOfType(ScheduledTaskHolder.class).values().stream()
            .flatMap(holder -> holder.getScheduledTasks().stream())
            .map(ScheduledTask::getTask)
            .filter(task -> task.getRunnable() instanceof ScheduledMethodRunnable runnable
                && jobClass.isInstance(runnable.getTarget()))
            .toList();
    }
}
