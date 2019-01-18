package es.andrewazor.dockertest.commands;

public interface CommandRegistry {
    void execute(String commandName, String[] args) throws Exception;
    boolean validate(String commandName, String[] args) throws Exception;
}
